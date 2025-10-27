package org.ncssar.rid2caltopo.data;

public class CtLineProperty {

    public int width;     // width in pixels
    public float opacity;   // 0-1
    public String color;     // RGB in #FFFFFF hex notation.
    public String pattern;   // solid, dash, ...

    public CtLineProperty() {
        width = 2;
        opacity = 1.0F;
        color = "#FF0000";
        pattern = "solid";
    }

    /** In case anyone ever wants to use something other than the defaults.
     * @param width = line width. Default == 2
     * @param opacity = 0-1.  Default = 1
     * @param color = Hex format RGB as in #FFFFFF. Default = #FF0000
     * @param pattern = Caltopo line types. Default is "solid"
     */
    public CtLineProperty(int width, float opacity, String color, String pattern) {
        this.width = width;
        this.opacity = opacity;
        this.color = color;
        this.pattern = pattern;
    }
} // end of CtLineProperty class spec.

