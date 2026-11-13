import SwiftUI

public enum WearBridgeTheme {
    // Deep OLED Obsidian Backgrounds
    public static let obsidianBackground = Color(red: 0.027, green: 0.035, blue: 0.055) // #07090E
    public static let cardBackground = Color(red: 0.067, green: 0.078, blue: 0.110) // #11141C
    public static let cardSurface = Color(red: 0.10, green: 0.12, blue: 0.16)
    public static let cardElevated = Color(red: 0.13, green: 0.15, blue: 0.20)

    // Specular Border & Stroke
    public static let strokeColor = Color.white.opacity(0.12)
    public static let strokeSubtle = Color.white.opacity(0.06)

    // Signature Accents
    public static let accentTeal = Color(red: 0.0, green: 0.85, blue: 0.80)
    public static let accentCyan = Color(red: 0.15, green: 0.65, blue: 1.0)
    public static let accentPurple = Color(red: 0.65, green: 0.35, blue: 0.95)
    public static let accentOrange = Color(red: 1.0, green: 0.55, blue: 0.15)
    public static let accentGreen = Color(red: 0.20, green: 0.85, blue: 0.45)
    public static let accentPink = Color(red: 1.0, green: 0.25, blue: 0.55)

    // Typography
    public static let textPrimary = Color.white
    public static let textSecondary = Color.white.opacity(0.65)
    public static let textTertiary = Color.white.opacity(0.40)

    // Gradients
    public static let heroGradients = LinearGradient(
        colors: [accentCyan.opacity(0.25), accentPurple.opacity(0.15), Color.clear],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    public static let weatherGradientDay = LinearGradient(
        colors: [Color.blue.opacity(0.35), Color.cyan.opacity(0.2)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

public struct GlassCardModifier: ViewModifier {
    var cornerRadius: CGFloat = 20
    var padding: CGFloat = 16

    public func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(WearBridgeTheme.cardBackground.opacity(0.72))
                    .background(
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .fill(.ultraThinMaterial)
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(WearBridgeTheme.strokeColor, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.35), radius: 14, x: 0, y: 7)
    }
}

public extension View {
    func glassCard(cornerRadius: CGFloat = 20, padding: CGFloat = 16) -> some View {
        self.modifier(GlassCardModifier(cornerRadius: cornerRadius, padding: padding))
    }
}
