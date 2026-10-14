package de.puffa.damagealert;

/** Minimale JSON-Helfer, damit keine zusaetzliche Bibliothek noetig ist. */
public final class Json {
	private Json() {
	}

	/** Gibt einen JSON-String inkl. Anfuehrungszeichen zurueck, oder {@code null}. */
	public static String string(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	/**
	 * Zahl auf 2 Nachkommastellen gerundet. Double.toString nutzt immer einen Punkt,
	 * unabhaengig von der Systemsprache (String.format wuerde auf Deutsch ein Komma liefern).
	 */
	public static String number(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "0";
		}
		double rounded = Math.round(value * 100.0) / 100.0;
		if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
			return Long.toString((long) rounded);
		}
		return Double.toString(rounded);
	}
}
