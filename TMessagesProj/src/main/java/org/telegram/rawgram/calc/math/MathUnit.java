package org.telegram.rawgram.calc.math;



public final class MathUnit {
    private final String code;
    private final UnitKind kind;
    private final String label;
    private final double offset;
    private final String russianLabel;
    private final double scale;

    public MathUnit(String str, String str2, UnitKind unitKind, double d, double d2, String str3) {
        this.code = str;
        this.label = str2;
        this.kind = unitKind;
        this.scale = d;
        this.offset = d2;
        this.russianLabel = str3;
    }

    /** A currency unit (scale 1, no offset, the same label in both languages). */
    public MathUnit(String code, String label) {
        this(code, label, UnitKind.CURRENCY, 1.0d, 0.0d, label);
    }

    public final String getCode() {
        return this.code;
    }


    public final UnitKind getKind() {
        return this.kind;
    }

    public final double getScale() {
        return this.scale;
    }

    public final String label(boolean russian) {
        return russian ? this.russianLabel : this.label;
    }

    public final double toBase(double value) {
        return (value * this.scale) + this.offset;
    }

    public final double fromBase(double value) {
        return (value - this.offset) / this.scale;
    }
}
