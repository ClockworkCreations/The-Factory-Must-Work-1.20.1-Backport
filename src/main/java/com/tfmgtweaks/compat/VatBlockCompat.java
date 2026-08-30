package com.tfmgtweaks.compat;

import com.drmangotea.tfmg.content.machinery.vat.base.VatBlock;

import java.lang.reflect.Field;

/**
 * VatBlock#vatType changed type between TFMG builds (String in vanilla,
 * ResourceLocation in CE), so ordinary compiled field access throws
 * NoSuchFieldError against whichever edition wasn't built against.
 * Reads it reflectively and normalizes to toString(), since callers
 * only ever compare values read this same way within one running game.
 */
public final class VatBlockCompat {

    private static volatile Field vatTypeField;

    private VatBlockCompat() {
    }

    public static String getVatType(VatBlock block) {
        try {
            Field f = vatTypeField;
            if (f == null) {
                synchronized (VatBlockCompat.class) {
                    f = vatTypeField;
                    if (f == null) {
                        f = VatBlock.class.getField("vatType");
                        f.setAccessible(true);
                        vatTypeField = f;
                    }
                }
            }
            Object value = f.get(block);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("tfmgtweaks: failed to read VatBlock#vatType reflectively", e);
        }
    }
}
