/**
 * The Atlas colours `index.css` defines, for the one place that cannot read a CSS variable: the
 * code editor's themes, registered once for both modes. Keep the two in step.
 */
export const atlasColors = {
  light: { accent: '#007aff', surfaceSecondary: '#f5f5f7', surfaceTertiary: '#fbfbfd' },
  dark: { accent: '#0a84ff', surfaceSecondary: '#1c1c1e', surfaceTertiary: '#2c2c2e' },
} as const
