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
        final boolean experimental;
        Selection(int width, int height, int fps, int ceaBit, int wfdProfile, int wfdLevel,
                  int codecProfile, int codecLevel, String codecName) {
            this(width, height, fps, ceaBit, wfdProfile, wfdLevel, codecProfile, codecLevel, codecName, false);
        }
        Selection(int width, int height, int fps, int ceaBit, int wfdProfile, int wfdLevel,
                  int codecProfile, int codecLevel, String codecName, boolean experimental) {
            this.width = width; this.height = height; this.fps = fps; this.ceaBit = ceaBit;
            this.wfdProfile = wfdProfile; this.wfdLevel = wfdLevel;
            this.codecProfile = codecProfile; this.codecLevel = codecLevel; this.codecName = codecName;
            this.experimental = experimental;
        }
    }

    // Prefer spatial resolution, then frame rate. Interlaced modes are not
    // offered: MediaProjection produces progressive frames.
    private static final int[][] MODES = {
            // IDs 17–22 are local preference keys, NOT WFD CEA bits. A zero
            // WFD level marks experimental modes with no legacy representation.
            {7680,4320,60,22,0,MediaCodecInfo.CodecProfileLevel.AVCLevel62},
            {7680,4320,30,21,0,MediaCodecInfo.CodecProfileLevel.AVCLevel61},
            {3840,2160,60,20,0,MediaCodecInfo.CodecProfileLevel.AVCLevel52},
            {3840,2160,30,19,0,MediaCodecInfo.CodecProfileLevel.AVCLevel51},
            {2560,1440,60,18,0,MediaCodecInfo.CodecProfileLevel.AVCLevel51},
            {2560,1440,30,17,0,MediaCodecInfo.CodecProfileLevel.AVCLevel5},
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
    static final int EXPERIMENTAL_MODES = (1 << 17) | (1 << 18) | (1 << 19)
            | (1 << 20) | (1 << 21) | (1 << 22);

    /** Return a copy for the settings UI; preferences are keyed by stable CEA bit. */
    static int[][] supportedModes() {
        int[][] copy = new int[MODES.length][];
        for (int i = 0; i < MODES.length; i++) copy[i] = MODES[i].clone();
        return copy;
    }

    static Selection select(String value, int disabledModes, boolean forceSinkLevels) throws IOException {
        String[] descriptors = value.split(",");
        for (int[] mode : MODES) {
            if ((disabledModes & (1 << mode[3])) != 0) continue;
            if (mode[4] == 0) {
                if (!forceSinkLevels) continue;
                Selection experimental = selectExperimental(value, mode);
                if (experimental != null) return experimental;
                continue;
            }
            Selection forcedCandidate = null;
            int forcedSinkLevel = 0;
            for (int index = 0; index < descriptors.length; index++) {
                String[] fields = descriptors[index].trim().split("\\s+");
                int start = index == 0 ? 2 : 0;
                if (fields.length < start + 3) continue;
                try {
                    int profiles = Integer.parseInt(fields[start], 16);
                    int levels = Integer.parseInt(fields[start + 1], 16);
                    long cea = Long.parseLong(fields[start + 2], 16);
                    if ((cea & (1L << mode[3])) == 0) continue;
                    if (!forceSinkLevels && !supportsLevel(levels, mode[4])) continue;
                    for (int wfdProfile : new int[]{1, 2}) {
                        if ((profiles & wfdProfile) == 0) continue;
                        int profile = wfdProfile == 1 ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                                : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                        String codec = findEncoder(mode[0], mode[1], mode[2], profile, mode[5]);
                        if (codec != null) {
                            Selection candidate = new Selection(mode[0], mode[1], mode[2], mode[3],
                                    wfdProfile, mode[4], profile, mode[5], codec);
                            if (supportsLevel(levels, mode[4])) return candidate;
                            // Prefer the profile whose advertised maximum is
                            // closest to the requested level when forcing.
                            int maximum = Integer.highestOneBit(levels & 0x1f);
                            if (forcedCandidate == null || maximum > forcedSinkLevel) {
                                forcedCandidate = candidate;
                                forcedSinkLevel = maximum;
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed sink video capabilities: " + value, e);
                }
            }
            if (forcedCandidate != null) {
                Log.w("VideoNegotiator", "Forcing " + mode[0] + "x" + mode[1]
                        + "p" + mode[2] + " above the sink's advertised H.264 level");
                return forcedCandidate;
            }
        }
        throw new IOException("No compatible progressive CEA video mode; sink=" + value);
    }

    static Selection selectCustom(String capabilities, VideoModePreferences.CustomMode custom) throws IOException {
        Selection advertised = select(capabilities, EXPERIMENTAL_MODES, false);
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codec.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(custom.width, custom.height, custom.fps)) continue;
                int level = 0;
                for (MediaCodecInfo.CodecProfileLevel supported : caps.profileLevels)
                    if (supported.profile == advertised.codecProfile) level = Math.max(level, supported.level);
                if (level == 0) continue;
                Log.w("VideoNegotiator", "Custom experimental " + custom.width + "x" + custom.height + "p" + custom.fps
                        + ": advertising " + advertised.width + "x" + advertised.height + "p" + advertised.fps);
                return new Selection(custom.width, custom.height, custom.fps, advertised.ceaBit,
                        advertised.wfdProfile, advertised.wfdLevel, advertised.codecProfile, level, codec.getName(), true);
            } catch (IllegalArgumentException ignored) { }
        }
        throw new IOException("No compatible AVC encoder for custom experimental video mode");
    }

    /**
     * Trial only: advertise a genuinely supported legacy mode, while encoding
     * the requested larger frame. This is not extended WFD negotiation.
     */
    private static Selection selectExperimental(String capabilities, int[] mode) {
        try {
            int onlyLegacy = EXPERIMENTAL_MODES;
            Selection advertised = select(capabilities, onlyLegacy, false);
            String codec = findEncoder(mode[0], mode[1], mode[2], advertised.codecProfile, mode[5]);
            if (codec == null) return null;
            Log.w("VideoNegotiator", "Experimental " + mode[0] + "x" + mode[1]
                    + "p" + mode[2] + ": advertising " + advertised.width + "x" + advertised.height
                    + "p" + advertised.fps + " to the display");
            return new Selection(mode[0], mode[1], mode[2], advertised.ceaBit,
                    advertised.wfdProfile, advertised.wfdLevel, advertised.codecProfile,
                    mode[5], codec, true);
        } catch (IOException e) {
            return null;
        }
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
