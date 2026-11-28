package se.deversity.vibetags.processor.internal;

/**
 * The comment style a module sub-marker is written in, which is whichever the file's own
 * format reads as a comment. The marker text itself is the {@code SUB_MARKER_*_FORMAT}
 * constants; this only chooses between them, once per merge.
 */
enum SubMarkers {
    MARKDOWN {
        @Override
        String open(String regionId) {
            return String.format(ModuleSidecar.SUB_MARKER_MD_FORMAT, regionId);
        }

        @Override
        String close(String regionId) {
            return String.format(ModuleSidecar.SUB_MARKER_MD_END_FORMAT, regionId);
        }
    },
    HASH {
        @Override
        String open(String regionId) {
            return String.format(ModuleSidecar.SUB_MARKER_HASH_FORMAT, regionId);
        }

        @Override
        String close(String regionId) {
            return String.format(ModuleSidecar.SUB_MARKER_HASH_END_FORMAT, regionId);
        }
    };

    static SubMarkers of(boolean htmlMarkers) {
        return htmlMarkers ? MARKDOWN : HASH;
    }

    abstract String open(String regionId);

    abstract String close(String regionId);

    /** {@code body} between this style's markers for {@code regionId}, each on a line of its own. */
    String wrap(String regionId, String body) {
        return open(regionId) + '\n' + body + '\n' + close(regionId) + '\n';
    }
}
