# This plugin's UI conventions

All paths are under `frontend/src/main/kotlin/com/rizkybusiness/ai/assistant/`.
Read the actual files before extending them; this page says where things go
and why, not what they currently contain.

## Where things live

| Concern | Location | Rule |
| --- | --- | --- |
| Colors | `chatApp/ui/utils/ChatAppColors.kt` | Nested objects per surface (`Panel`, `Text`, `MessageBubble`, `Prompt`). Every value is a `JBColor(light, dark)` pair or a theme key lookup via `UIManager.getColor`/`JBColor.namedColor`. Getters (`get() =`) where the value must follow a live theme switch. |
| Sizes | `chatApp/ui/utils/ChatUIConstants.kt` | Unscaled `Int` constants grouped per component (`Spacing`, `MessageBubble`, `Input`, `Button`, ...). Call sites wrap them in `JBUI.scale(...)`; the constants themselves are never scaled. |
| Icons | `chatApp/ui/utils/ChatAppIcons.kt` | Grouped per feature (`Header`, `Search`, `Prompt`). Values are `AllIcons.*` references. Plugin-own icons go through `ModularPluginIcons.kt` and `resources/icons/`. |
| Strings | `resources/messages/ModularPluginFrontendBundle.properties` via `ModularPluginFrontendBundle.message("key", args)` | Dotted keys by feature: `chat.header.*`, `chat.prompt.*`, `chat.mention.*`, `chat.models.*`. Sentence case. HTML escaped with `StringUtil.escapeXmlEntities` when rendered in `<html>` labels. |
| Tool window | `toolWindow/ModularPluginToolWindowFactory.kt` | Registers content; the chat UI itself is composed in `chatApp/ChatAppSample.kt`. |
| Chat surfaces | `chatApp/ui/` | `ChatHeader`, `ChatToolbar`, `ChatList`, `MessageItem` (bubbles), `PromptInput`, `InputChooserPopup` (`@` mention popup), `ContextFilesBar`, `ModelsErrorBanner`, `ThinkingIndicator`, `ThinkingSection`, `markdown/` (rendered answers). |
| State | `chatApp/viewmodel/` | `ChatViewModel`, `Frontend*Model` classes hold RPC-fed state. UI classes observe; they never call RPC APIs directly for data they could get from the view model. |
| Scopes | `CoroutineScopeHolder.kt` | UI coroutines use `CoroutineScopeHolder.createScope`; never `GlobalScope` or ad-hoc scopes. |

## Established patterns to copy

**Message bubble** (`MessageItem.kt`): a `JPanel` with `BoxLayout.Y_AXIS`,
`isOpaque = false`, compound empty borders for outer margin plus inner
padding, and a custom `paintComponent` drawing a `RoundRectangle2D` in the
bubble color. It re-wraps to the viewport width through
`updateAvailableWidth(viewportWidthPx)` because a tool window has no
horizontal scrollbar; anything that shows long text must do the same.
Error messages are the same bubble with `errorBackground`/`errorBorder`, so
errors are visibly part of the conversation, not a separate widget.

**Banner** (`ModelsErrorBanner.kt`): opaque panel, `BorderLayout`, matte
1px top and bottom border in the error border color, `JBFont.small()`, full
error text in an `<html>` label, hidden when there is no error. Sits above
the chat list. Copy it for any "state of this context" message; use a
balloon notification instead when the message has no component to attach to.

**Popup** (`InputChooserPopup.kt`): `JBPopupFactory` list popup with
`JBList` and `SimpleListCellRenderer`, anchored with `RelativePoint` to the
caret. Copy for any inline chooser.

**Streaming updates**: the backend grows one message in place; UI classes
expose an `updateFrom(...)` method that diffs against the last content and
calls `revalidate()`/`repaint()` only when something changed. Do not rebuild
the whole list per token.

## Adding to the conventions

- A new color: add it to the matching nested object in `ChatAppColors`. If
  it carries meaning (selection, error, highlight), prefer
  `JBColor.namedColor("CodeAssistant.<Component>.<property>", JBColor(l, d))`
  so themes can override it; keep the fallback pair consistent with IntelliJ
  Light and Darcula.
- A new size: add an unscaled constant to `ChatUIConstants` under the
  component's object; document with a short comment when the value is a
  constraint rather than a preference (see `MIN_CONTENT_WRAP_WIDTH`).
- A new string: add the key next to its siblings in the bundle; never
  concatenate user-visible sentences in Kotlin.
- A new icon: `AllIcons` first. Own SVGs are added in 16px (and 20px for the
  tool window button), plus `_dark` variants, and referenced from
  `ModularPluginIcons`.
