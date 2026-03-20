import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

class AppTheme {
  // ── Palette ──────────────────────────────────────────────────────────────
  static const Color background    = Color(0xFF0E0F11);
  static const Color surface       = Color(0xFF16181C);
  static const Color surfaceRaised = Color(0xFF1E2028);
  static const Color border        = Color(0xFF2A2D35);
  static const Color accent        = Color(0xFF5B8EF0);
  static const Color accentDim     = Color(0xFF2D4A8A);
  static const Color textPrimary   = Color(0xFFE8EAF0);
  static const Color textSecondary = Color(0xFF8B909E);
  static const Color textMuted     = Color(0xFF4A4F5E);
  static const Color userBubble    = Color(0xFF1C2742);
  static const Color aiBubble      = Color(0xFF16181C);
  static const Color error         = Color(0xFFE05C5C);
  static const Color success       = Color(0xFF4CAF7D);

  // ── Typography ───────────────────────────────────────────────────────────
  static TextStyle get displayFont =>
      GoogleFonts.spaceGrotesk(color: textPrimary);

  static TextStyle get bodyFont =>
      GoogleFonts.jetBrainsMono(color: textPrimary);

  // ── Theme ─────────────────────────────────────────────────────────────────
  static ThemeData get dark {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: background,
      colorScheme: const ColorScheme.dark(
        surface: surface,
        primary: accent,
        secondary: accentDim,
        error: error,
        onPrimary: textPrimary,
        onSurface: textPrimary,
      ),
      textTheme: GoogleFonts.interTextTheme(
        const TextTheme(
          bodyLarge:   TextStyle(color: textPrimary,   fontSize: 15, height: 1.6),
          bodyMedium:  TextStyle(color: textPrimary,   fontSize: 14, height: 1.5),
          bodySmall:   TextStyle(color: textSecondary, fontSize: 12, height: 1.4),
          labelLarge:  TextStyle(color: textPrimary,   fontSize: 14, fontWeight: FontWeight.w600),
          labelMedium: TextStyle(color: textSecondary, fontSize: 12),
          titleMedium: TextStyle(color: textPrimary,   fontSize: 16, fontWeight: FontWeight.w600),
          titleLarge:  TextStyle(color: textPrimary,   fontSize: 20, fontWeight: FontWeight.w700),
        ),
      ),
      dividerColor: border,
      dividerTheme: const DividerThemeData(color: border, thickness: 1),
      appBarTheme: AppBarTheme(
        backgroundColor: background,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: GoogleFonts.spaceGrotesk(
          color: textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.3,
        ),
        iconTheme: const IconThemeData(color: textSecondary),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surfaceRaised,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: accent, width: 1.5),
        ),
        hintStyle: const TextStyle(color: textMuted, fontSize: 14),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: ButtonStyle(
          foregroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.disabled)) return textMuted;
            return textSecondary;
          }),
        ),
      ),
    );
  }
}
