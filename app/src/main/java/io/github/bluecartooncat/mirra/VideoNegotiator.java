package io.github.bluecartooncat.mirra;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.IOException;
import java.util.Locale;

/** Select a progressive CEA mode supported by the sink and a local AVC encoder. */
final class VideoNegotiator {
    static final class Selection {
        final int width, height, fps, ceaBit, wfdProfile, wfdLevel, codecProfile, codecLevel;
        final String codecName;
        Selection(int width, int height, int fps, int ceaBit, int wfdProfile, int wfdLevel,
                  int codecProfile, int codecLevel, String codecName) {
            this.width = width; this.height = height; this.fps = fps; this.ceaBit = ceaBit;
            this.wfdProfile = wfdProfile; this.wfdLevel = wfdLevel;
            this.codecProfile = codecProfile; this.codecLevel = codecLevel; this.codecName = codecName;
        }
    }

    // Prefer spatial resolution, then frame rate. Interlaced modes are not
    // offered: MediaProjection produces progressive frames.
    private static final int[][] MODES = {
            {1920,1080,60,8,16,MediaCodecInfo.CodecProfileLevel.AVCLevel42},
            {1920,1080,50,13,16,MediaCodecInfo.CodecProfileLevel.AVCLevel42},
            {1920,1080,30,7,4,MediaCodecInfo.CodecProfileLevel.AVCLevel4},
            {1920,1080,25,12,4,MediaCodecInfo.CodecProfileLevel.AVCLevel4},
            {1920,1080,24,15,4,MediaCodecInfo.CodecProfileLevel.AVCLevel4},
            {1280,720,60,6,2,MediaCodecInfo.CodecProfileLevel.AVCLevel32},
            {1280,720,50,11,2,MediaCodecInfo.CodecProfileLevel.AVCLevel32},
            {1280,720,30,5,1,MediaCodecInfo.CodecProfileLevel.AVCLevel31},
            {1280,720,25,10,1,MediaCodecInfo.CodecProfileLevel.AVCLevel31},
            {720,576,50,3,1,MediaCodecInfo.CodecProfileLevel.AVCLevel31},
            {720,480,60,1,1,MediaCodecInfo.CodecProfileLevel.AVCLevel31},
            {640,480,60,0,1,MediaCodecInfo.CodecProfileLevel.AVCLevel31}
    };

    static Selection select(String value) throws IOException {
        String[] descriptors = value.split(",");
        for (int[] mode : MODES) {
            for (int index = 0; index < descriptors.length; index++) {
                String[] fields = descriptors[index].trim().split("\\s+");
                int start = index == 0 ? 2 : 0;
                if (fields.length < start + 3) continue;
                try {
                    int profiles = Integer.parseInt(fields[start], 16);
                    int levels = Integer.parseInt(fields[start + 1], 16);
                    long cea = Long.parseLong(fields[start + 2], 16);
                    if ((cea & (1L << mode[3])) == 0 || !supportsLevel(levels, mode[4])) continue;
                    for (int wfdProfile : new int[]{1, 2}) {
                        if ((profiles & wfdProfile) == 0) continue;
                        int profile = wfdProfile == 1 ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                                : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                        String codec = findEncoder(mode[0], mode[1], mode[2], profile, mode[5]);
                        if (codec != null) return new Selection(mode[0], mode[1], mode[2], mode[3],
                                wfdProfile, mode[4], profile, mode[5], codec);
                    }
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed sink video capabilities: " + value, e);
                }
            }
        }
        throw new IOException("No compatible progressive CEA video mode; sink=" + value);
    }

    private static boolean supportsLevel(int advertised, int minimum) {
        return (advertised & (0x1f & ~(minimum - 1))) != 0;
    }

    private static String findEncoder(int width, int height, int fps, int profile, int level) {
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codec.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps)) continue;
                for (MediaCodecInfo.CodecProfileLevel supported : caps.profileLevels) {
                    if (supported.profile == profile && supported.level >= level) return codec.getName();
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    static String hex(int value, int digits) { return String.format(Locale.US, "%0" + digits + "x", value); }
}
