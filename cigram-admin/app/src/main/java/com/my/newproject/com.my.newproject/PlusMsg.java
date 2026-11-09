package com.my.newproject;

/** Text helpers of the Plus codes screen (no Android classes, so they can be tested on a plain JVM). */
final class PlusMsg {
    private PlusMsg() { }

    /** The message sent to the customer. */
    static String message(String name, String code, int months, int validDays) {
        StringBuilder m = new StringBuilder();
        m.append(name.length() > 0 ? "أهلاً " + name + " 👋" : "أهلاً 👋").append('\n');
        m.append("تم اعتماد اشتراكك في Cigram Plus ✅").append("\n\n");
        m.append("كود التفعيل:\n").append(code).append("\n\n");
        m.append("الباقة: ").append(months).append(" أشهر").append('\n');
        m.append("لتفعيله: افتح تطبيق سيكرام ← حسابي ← «ضع كود التفعيل» ← الصق الكود واضغط تفعيل.").append('\n');
        m.append("مدة الاشتراك تبدأ من لحظة التفعيل وتظهر لك تاريخ انتهائها.").append('\n');
        m.append("الكود يعمل مرة واحدة فقط على حساب واحد، فلا تشاركه.");
        if (validDays > 0) m.append('\n').append("يُفعَّل خلال ").append(validDays).append(" يوماً من الآن.");
        return m.toString();
    }

    /** 0770... / 770... / +964... / 00964... -> 964770...; anything else is returned as digits. */
    static String normalizePhone(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.startsWith("964")) return d;
        if (d.startsWith("0") && d.length() >= 10) return "964" + d.substring(1);
        if (d.startsWith("7") && d.length() == 10) return "964" + d;
        return d;
    }
}
