package app.aaps.shared.impl.weardata

enum class JsonKeyValues(val key: String) {
    GONE("gone"),
    VISIBLE("visible"),
    CENTER("center"),
    LEFT("left"),
    RIGHT("right"),
    SANS_SERIF("sans_serif"),
    DEFAULT("default"),
    DEFAULT_BOLD("default_bold"),
    MONOSPACE("monospace"),
    SERIF("serif"),
    ROBOTO_CONDENSED_BOLD("roboto_condensed_bold"),
    ROBOTO_CONDENSED_LIGHT("roboto_condensed_light"),
    ROBOTO_CONDENSED_REGULAR("roboto_condensed_regular"),
    ROBOTO_SLAB_LIGHT("roboto_slab_light"),
    NORMAL("normal"),
    BOLD("bold"),
    BOLD_ITALIC("bold_italic"),
    ITALIC("italic"),
    BGCOLOR("bgColor"),             // optional 2 hex digits alpha suffix, ie "bgColor#80" is half transparent
    BGCOLOR_EXT1("bgColor_Ext1"),   // same optional alpha suffix, ie "bgColor_Ext1#80"
    BGCOLOR_EXT2("bgColor_Ext2"),   // same optional alpha suffix, ie "bgColor_Ext2#80"
    SGV_LEVEL("sgvLevel"),
    SGV_LEVEL_EXT1("sgvLevel_Ext1"),
    SGV_LEVEL_EXT2("sgvLevel_Ext2"),
    RESERVOIR_LEVEL("reservoirLevel"),
    RESERVOIR_LEVEL_EXT1("reservoirLevel_Ext1"),
    RESERVOIR_LEVEL_EXT2("reservoirLevel_Ext2"),
    IOB("iob"), // valueKey for dynData: insulin on board in U, bolus and basal together
    IOB_EXT1("iob_Ext1"),
    IOB_EXT2("iob_Ext2"),
    IOB_BOLUS("iob_bolus"), // valueKey for dynData: the bolus part of iob, in U
    IOB_BOLUS_EXT1("iob_bolus_Ext1"),
    IOB_BOLUS_EXT2("iob_bolus_Ext2"),
    IOB_BASAL("iob_basal"), // valueKey for dynData: the basal part of iob, in U
    IOB_BASAL_EXT1("iob_basal_Ext1"),
    IOB_BASAL_EXT2("iob_basal_Ext2"),
    COB("cob"), // valueKey for dynData: carbs on board in grams
    COB_EXT1("cob_Ext1"),
    COB_EXT2("cob_Ext2"),
    PREF_UNITS("key_units"),
    PREF_DARK("key_dark"),
    PREF_MATCH_DIVIDER("key_match_divider"),

    // Values for JsonKeys.IMAGEFIT, named after android.widget.ImageView.ScaleType. They describe
    // the square the image is drawn into, because the library always crops the image to fill that
    // square - see ComplicationImageFit.
    FIT_CENTER("fit_center"),       // square of the slot's smaller side, centred - the default
    CENTER_CROP("center_crop"),     // square of the slot's larger side: fills the slot, sides cut off
    FIT_XY("fit_xy")                // stretched to the slot's width and height, aspect not kept
}