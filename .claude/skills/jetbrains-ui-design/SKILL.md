---
name: jetbrains-ui-design
description: Design and build IntelliJ Platform (Swing / Kotlin UI DSL) user interface for this plugin's frontend module with the visual quality of a deliberate design pass — tool window layouts, chat bubbles, headers and toolbars, popups, banners, empty states, settings panels, icons, and copy. Use it whenever a task touches anything the user sees in the IDE, even when the request is phrased as "add a button", "make it look better", "the error should be visible", "polish", "align", "spacing", "dark theme", or "restyle" — not only when the word "design" appears. Replaces the web-oriented frontend-design skill: same design discipline, JetBrains toolkit and guidelines instead of CSS.
---

# JetBrains UI design

Approach every UI change as the designer responsible for how this plugin feels
inside the IDE. The bar is not "renders without exceptions". It is: a JetBrains
designer opening the tool window would believe it ships with the product, and
a user would find the one thing that is ours memorable rather than noisy.

Two things make IDE UI different from web UI, and both shape this skill:

1. **The host defines the visual language.** Fonts, colors, spacing, icons and
   control shapes come from the IDE theme and scale with the user's settings.
   Distinctiveness comes from composition, hierarchy and copy, not from a custom
   palette or typeface. A plugin that brings its own look reads as foreign, and
   breaks the moment the user switches to a dark, high-contrast or custom theme.
2. **The frontend is thin by architecture.** In split mode only `frontend/` runs
   on the client. UI code never touches PSI, VFS, indexes or the model endpoint;
   it renders state that arrives over RPC. See CLAUDE.md "The boundary rule".
   Design the UI around the data the RPC already provides, or extend the RPC
   first (`/new-rpc`).

Read `references/project-conventions.md` before touching existing UI: the
plugin already has a color object, a spacing object, an icon object and a
message bundle, and a change that bypasses them is the first thing a reviewer
flags.

## Ground the design in the subject

This is an AI chat assistant living in a tool window beside the editor. Its
job is to let a developer ask, read a streamed answer, and reference project
files, without leaving their work. That subject matter decides the aesthetic:
quiet, dense enough to respect screen space, honest about state (streaming,
thinking, erroring, disconnected), and visibly part of the IDE.

Before designing, name in one sentence what the change is for and who sees it
in what state. "The models banner appears when the host cannot reach Ollama,
above the chat, until the user fixes the URL" is a brief. "Add an error
banner" is not.

## Design principles for IDE UI

**Hierarchy through built-in type styles.** The IDE ships a small type scale
(`JBFont.h1/h2/regular/medium`, bold variants) that the user can rescale. Use
those roles and nothing else. Reach for bold before size; reach for size only
where the guidelines say a header lives. Never hardcode a point size.

**Color means state.** Every color is a `JBColor` light/dark pair, and any
color that carries meaning should be a `JBColor.namedColor` with a theme key
so custom themes can restyle it. Use color for state only: selection, error,
warning, link, disabled. Decorative tints, gradient washes, brand accents and
"a splash of color to liven it up" are the tells of UI that was not designed
for an IDE. Chat bubbles distinguishing "mine" from "assistant" are state.

**Space is the main structural device.** Insets group; borders separate. The
guidelines are precise about this (see the digest): controls in one group sit
close, groups are separated by vertical insets, and a border or separator
appears only when spacing alone cannot express the boundary. All spacing goes
through `JBUI.scale` or `JBUI.Borders.empty(...)` so HiDPI and font scaling
keep proportions.

**Icons come from `AllIcons` first.** The platform icon set is large and users
already know it. A new icon is justified only for a concept the platform does
not have, and then it follows the icon style guide (flat, 2px stroke, 16px
with a 1px transparent border, monochrome for tool window and toolbar use).

**Copy is part of the layout.** Short, sentence case, present tense, no
addressing the user, no "click" or "please". Empty states say what is missing
and offer one action. Banners state the problem and the fix in at most two
sentences. Every string lives in the message bundle from the first commit.

**Motion answers an action.** The only ambient animation in this UI is the
thinking indicator, and it exists to show that work is happening. Do not add
transitions, fades or hover choreography; Swing does not expect them and the
IDE around the tool window does not have them.

**Spend boldness in one place.** Pick the single element that carries the
plugin's identity, currently the chat bubble treatment, and keep everything
around it (header, toolbar, input, banners) indistinguishable from platform
chrome. If two things are trying to be memorable, one of them is wrong.

## Process: brief, plan, review, build, critique

1. **Brief.** One sentence, as above. Identify the IDE states the UI must
   handle: empty, loading, streaming, error, disconnected, narrow tool window,
   light and dark theme.
2. **Plan.** Sketch the layout as an ASCII wireframe. Decide alignment (the
   guidelines default to left-aligned, label-above only when width is short),
   which type roles each text uses, and which colors carry state. Name the one
   memorable element.
3. **Review the plan against the platform.** For each choice, ask: would a
   JetBrains tool window do it this way? Where the answer is no, the reason
   must be the subject matter, not preference. Check the plan against
   `references/ui-guidelines-digest.md` (layout, typography, empty state,
   banner, tool window, writing) and against the calibration list below.
4. **Build** with the toolkit in `references/platform-toolkit.md`: platform
   components (`JBLabel`, `JBPanel`, `JBScrollPane`, `JBList`, Kotlin UI DSL
   for forms), `JBUI` for all sizes, `JBColor` for all colors, `AllIcons` for
   icons, bundle keys for all strings. Keep painting code (custom
   `paintComponent`) confined to the memorable element.
5. **Critique with a screenshot.** Run the sandbox (`/run-sandbox`) in both
   light and dark themes and at a narrow tool window width, and look. Text that
   wraps into a ribbon, a color that vanishes in Darcula, a border where an
   inset would do, a label that says "Please enter": fix before the PR. Then
   remove one thing. Finally run `/boundary-check` and `/code-quality`.

## Calibration: what generated IDE UI tends to do

These are defaults, not choices. Where the request pins one down, follow it;
otherwise do not spend your freedom here.

- Hardcoded `Color(…)` or `Font(…, 13)` anywhere outside a `JBColor` pair or
  `JBFont` call.
- Rounded cards with drop shadows for every group; the IDE has no shadows
  outside popups.
- A separator or titled border around every section.
- Uppercase labels, "eyebrow" captions, or emoji in labels and buttons.
- Buttons where a link or a toolbar action is the platform convention, and
  a big primary button inside a tool window.
- Tooltip-only error reporting; the CLAUDE.md rule is that model-source
  errors are visible in the chat as a distinct bubble or banner.
- Fixed pixel widths that ignore the tool window being resized; bubbles and
  text must re-wrap to the viewport (see `MessageBubble.updateAvailableWidth`).
- A layout that only works in IntelliJ IDEA's default theme and font size.

## Definition of done for a UI change

- Strings in the bundle, colors in `ChatAppColors` (as `JBColor` pairs,
  named where they carry meaning), sizes in `ChatUIConstants` and scaled.
- Verified visually in light and dark, and at narrow width, in the sandbox.
- Only `frontend/` changed for rendering; any data need went through `shared/`
  RPC and the backend.
- `DOCUMENTATION.md` updated if a user-visible feature or default changed.
