# JetBrains UI Guidelines digest

Condensed from https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html
(pages: typography, layout, tool window, empty state, banner, platform theme
colors, icon style, writing short). Fetch the full page with the `defuddle`
skill when a detail here is not enough:
`https://plugins.jetbrains.com/docs/intellij/<page>.html`.

## Typography (`typography`)

- UI font is Inter, 13 pt by default, user-changeable; all other sizes
  derive from it. Editor font is JetBrains Mono.
- Built-in styles, use these and nothing else: H1 (+5, bold) main page
  header; H2 (+3, bold) small page header; Default for all controls;
  Default semibold for headers in dialogs, popups, notifications, tool
  windows; Paragraph (default, +3 line height) for multiline description;
  Medium (−1) help text; Medium semibold group headers in popups.
- Custom size only as default ± constant. Never hardcode a size.
- Code snippets, completion and documentation popups use the editor font.

## Layout (`layout`)

- Labeled inputs: labels left-aligned; input boxes aligned on the left when
  labels are similar length; do not align when one label is twice the other.
- Label above the box only when the box is long and width is limited.
- Up to two columns of short inputs; never more.
- Independent checkboxes on separate lines; 2–3 short ones may share a line.
  Four or more can go in columns (2 columns up to 30 chars, 3 up to 15).
  Radio buttons of one group never split into columns.
- Independent buttons and links left-aligned; 2–3 may share a line; never
  in columns.
- Lists, trees, tables: width so common values are visible; nothing
  independent to their right (it reads as master-detail).
- Dependent controls: 2–3 short ones on the main control's line; otherwise
  below, aligned to the input box (or the label with a horizontal inset
  when the label is long). Dependents of a checkbox align to its label.
- Vertical insets group; an unnecessary inset creates a false group. The
  inset inside a group must be smaller than the inset between groups, in
  both axes.

## Tool window (`tool-window`)

- Name short, title case, two words at most; abbreviation for stripes if a
  common one exists.
- Button icon 16×16 and 20×20, grey, monochrome. Badge over the icon for new
  feedback; never swap the icon.
- Tabs for similar instances (sessions, results); closable when they are
  instances.
- Toolbar for frequently used actions and filters.
- Hide the button by default unless the window is basic for every project.
- Vertical windows suit trees; horizontal suit tables, wide content and
  master-detail.
- Show an empty state when there is no content yet.

## Empty state (`empty-state`)

- Explain the state: `No [entity] added.` Use the entity names already in
  the UI.
- One or two actions as links, with shortcuts where they exist; a list or
  table layout only when every starting point must be shown.
- If a link opens a toolbar menu, open it where the toolbar button would.
- No action possible: describe the required steps in text.
- Hide the toolbar when its actions do not apply to the empty state.
- Periods between sentences, no period after the action link, ellipsis when
  the link opens a dialog. Sentence case. No "click", "press", "add new".
- Non-breaking spaces between action names and shortcuts.

## Banner (`banner`)

- For the state of a specific context (file tab, tool window, dialog) when
  attention is needed but not immediately. No component to tie it to: use a
  balloon notification instead.
- Types: Information (optional improvement), Warning (may affect workflow),
  Error (required to unblock).
- Message short and descriptive, two sentences at most, sentence case.
- At most two actions, two to three words each; use the built-in Hide.
- Optional 16×16 icon. Placed at the top of the related component; may float
  over content when vertical shifting must be avoided.

## Platform theme colors (`platform-theme-colors`)

- Two default themes: IntelliJ Light and Darcula. Every color key has a
  value in each.
- Reuse an existing color first (LaF Defaults dialog in internal mode).
- Reuse an existing key only when it fits semantically; otherwise create
  `ComponentName.property` following the key naming scheme, so theme authors
  restyling one thing do not restyle yours by accident.
- Implementation: `JBColor.namedColor("Key.name", JBColor(light, dark))`.

## Icon style (`icons-style`)

- Flat, geometric, straight corners; 45°/90° (or 30°/60°) angles.
- 16×16 default with 1 px transparent border (visible area 14×14); tool
  window 13×13; gutter and status bar 12×12; dialogs 32×32 with 2 px border.
- 2 px stroke; align to the pixel grid; minimal anchors.
- Same visual weight across a set: round shapes 2 px larger than squares.
- Modifier badges 6–9 px, bottom-right by default, 1–2 px gap.
- Arrows: filled 90° head, 2 px body, horizontal/vertical/45°/round.

## Writing short (`writing-short`)

- Simple constructions, present tense, active voice, one idea per sentence.
- Remove generic words (general, advanced, options) and obvious verbs
  (specify, enter, click).
- Do not address the user ("you can", "please").
- Move a repeated word to the front once instead of repeating it per label.
- Translate implementation language into what the user sees and does.
- Reread as a first-time user; if something needs explaining, explain it in
  the label or a comment, not in a tooltip only.
