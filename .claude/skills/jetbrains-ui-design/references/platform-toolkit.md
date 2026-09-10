# IntelliJ Platform UI toolkit cheat sheet

The APIs that make a Swing UI look and behave like the IDE. Everything here
is platform-level (`com.intellij.ui`, `com.intellij.util.ui`,
`com.intellij.openapi.ui`) and safe for the frontend module and for all
IntelliJ-based IDEs.

## Sizing and HiDPI

```kotlin
JBUI.scale(8)                         // Int px scaled for HiDPI + user font scale
JBUI.Borders.empty(top, left, bottom, right)   // scaled empty border
JBUI.Borders.empty(vertical, horizontal)
JBUI.Borders.compound(outer, inner)
JBUI.Borders.customLine(color, top, left, bottom, right)  // 1px lines
JBUI.insets(4, 8)                     // scaled Insets for layouts
JBUI.size(24, 24)                     // scaled Dimension
JBUI.emptySize()
```

Never pass raw ints to `Dimension`, `Insets`, `setBorder` or `BorderFactory`
for anything that should scale. `BorderFactory.createMatteBorder` is fine for
1px lines (it is common in platform code), but pair it with `JBUI.Borders`
for padding.

## Typography

```kotlin
JBFont.h1().asBold()      // main page header (rare in a tool window)
JBFont.h2().asBold()      // section header
JBFont.regular()          // everything by default; labels, inputs, lists
JBFont.regular().asBold() // header of a dialog, popup, tool window section
JBFont.medium()           // help text (default −1)
JBFont.medium().asBold()  // group headers inside popups
JBFont.small()            // status-like secondary text (default −2)
JBFont.label().deriveFont(...) // only to change style, never size by number
```

Derive from these; do not construct `Font(...)`. Line length: keep multiline
text under roughly 80 characters per line; wrap by giving the component a
width (see the bubble's wrap logic), not by inserting line breaks.

## Colors and themes

```kotlin
JBColor(Color(227, 242, 253), Color(37, 55, 70))   // light, dark pair
JBColor.namedColor("CodeAssistant.Bubble.background", JBColor(0xE3F2FD, 0x253746))
JBColor.PanelBackground; JBColor.foreground(); JBColor.border(); JBColor.GRAY
UIUtil.getPanelBackground(); UIUtil.getLabelForeground()
UIUtil.getContextHelpForeground()     // secondary/help text color
JBUI.CurrentTheme.*                   // component-specific theme colors
                                      // e.g. CurrentTheme.Banner.ERROR_BACKGROUND
                                      // CurrentTheme.Link.Foreground.ENABLED
                                      // CurrentTheme.ToolWindow.background()
UIManager.getColor("Panel.background") // theme key lookup (fallback to JBColor)
```

The LaF Defaults dialog (internal mode, Tools | Internal Actions | UI) lists
every theme key and its current value; use it to find an existing key before
inventing a color. Reuse a key only when its meaning matches, otherwise a
custom theme will recolor your component unexpectedly.

Test colors in both IntelliJ Light and Darcula; a `Gray._245` background
that looks subtle in light mode is a white slab in dark mode unless paired.

## Components

| Need | Use | Not |
| --- | --- | --- |
| Label | `JBLabel` (supports `<html>`, `copyable`, `setAllowAutoWrapping`) | `JLabel` |
| Panel | `JBPanel`, `JPanel` with `isOpaque = false` on transparent surfaces | `JPanel` with hardcoded background |
| Scrolling | `JBScrollPane` | `JScrollPane` |
| Text input | `JBTextField`, `JBTextArea`, `ExpandableTextField`, `EditorTextField` for code | raw Swing text components |
| Lists / tables / trees | `JBList`, `JBTable`, `Tree` with `SimpleListCellRenderer` / `ColoredListCellRenderer` | custom renderers with painted backgrounds |
| Toolbar | `ActionManager.createActionToolbar` with an `ActionGroup` of `AnAction`s (`targetComponent` set) | rows of `JButton`s |
| Icon button | `ActionButton` or `InplaceButton` | `JButton` with an icon and stripped borders |
| Popup / chooser | `JBPopupFactory.createPopupChooserBuilder`, `createListPopup`, `createComponentPopupBuilder` | `JPopupMenu`, undecorated `JWindow` |
| Banner in a component | `InlineBanner` / `EditorNotificationPanel` (`Status.Error/Warning/Info`) or the project's `ModelsErrorBanner` pattern | a red label |
| Global notification | `NotificationGroupManager` → `Notification` (balloon) | `JOptionPane` |
| Empty state | `JBPanelWithEmptyText` / `StatusText` with `appendLine` and a link action | a centered gray label |
| Progress | `AsyncProcessIcon`, a plain `JProgressBar` (the LaF themes it), or the project's `ThinkingIndicator` | busy cursors |
| Separator | `SeparatorComponent`, `TitledSeparator`, `JBUI.Borders.customLine` | `JSeparator` with default insets |
| Combo box | `ComboBox<T>` from `com.intellij.openapi.ui` | `JComboBox` |
| Link | `ActionLink`, `HyperlinkLabel`, `BrowserLink` | underlined `JLabel` with a mouse listener |
| Tooltip | `HelpTooltip` for rich help, `toolTipText` for a name and shortcut | custom tooltip windows |

## Forms and settings panels: Kotlin UI DSL v2

```kotlin
panel {
    row(MyBundle.message("settings.baseUrl")) {
        textField().bindText(state::baseUrl).columns(COLUMNS_LARGE)
            .comment(MyBundle.message("settings.baseUrl.comment"))
    }
    group(MyBundle.message("settings.context")) {
        row { checkBox(MyBundle.message("settings.includeOpenFiles")).bindSelected(state::includeOpenFiles) }
        indent { row(MyBundle.message("settings.budget")) { intTextField(0..200_000).bindIntText(state::budget) } }
    }
}
```

The DSL encodes the layout guidelines (label alignment, insets, dependent
control indentation, comments as help text). Use it for anything form-shaped
in settings or dialogs. Chat surfaces stay hand-laid Swing because they are
not forms.

## Threading and lifecycle (UI side)

- Mutate Swing only on the EDT: collect flows with `Dispatchers.EDT`
  (`withContext(Dispatchers.EDT)` / `launch(Dispatchers.EDT)`), or
  `invokeLater` from non-coroutine code.
- Use `CoroutineScopeHolder.createScope` for UI coroutines and tie the scope
  to the tool window content's `Disposable`; cancel on dispose.
- `revalidate()` then `repaint()` after structural changes; `repaint()` alone
  after visual-only changes. Batch per streamed chunk, not per token, if
  layout cost shows up.
- Painting: `g.create() as Graphics2D`, set antialiasing, dispose the copy.
  Keep custom painting to the one memorable component.

See `/code-quality` for the full threading and lifecycle checklist.

## Accessibility and keyboard

- Every action reachable by keyboard: `registerKeyboardAction` /
  `DumbAwareAction.registerCustomShortcutSet`; the send button already maps
  Enter and Shift+Enter, keep that convention.
- `accessibleContext.accessibleName` on icon-only buttons and custom
  components; screen readers read the tooltip otherwise, so always set one.
- Focus order follows reading order; `focusTraversalPolicy` only when the
  default breaks.
- Respect `UISettings` font size and HiDPI by scaling everything (above); do
  not clamp the tool window to a minimum size larger than roughly 200 px wide.
