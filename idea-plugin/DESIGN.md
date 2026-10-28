# DocCache IDEA Review

## 1. Atmosphere & Identity
Native IntelliJ review workspace, not a web dashboard. The editor owns code;
the navigation pane owns findings. Preserve the installed IDE look and feel.

## 2. Color
Use UIUtil panel/text/context-help colors, JBColor border and IDE list selection.
Errors use the IDE error foreground. No custom accent stripes or fixed dark text.
Delivery states always have text, not just color.

## 3. Typography
IDE label font for review text; IDE small font only for metadata. Code uses the
native diff editor's configured font and syntax highlighting. Text is selectable
and wraps to the viewport; paths in lists may truncate with a full tooltip.

## 4. Spacing & Layout
JBUI-scaled 4/8/12 spacing. One editor tab per repository and MR. Resizable
horizontal split: navigation at 32%, diff at 68%. Navigation has category tabs
and a resizable vertical split between list and selected finding. Each list,
detail and diff owns its scrolling. The composer is collapsed until requested.
Narrow editor groups can resize the split; no fixed minimum window width.

## 5. Components
- MR editor: native FileEditor lifecycle, reusable by project/repository/iid.
- Review browser: separate Drafts, Discussions and Files lists; keyboard selection
  updates the full detail and diff. Empty categories explain what to do next.
- Detail: existing edit/delete/reply and delivery controls, readable wrapped text.
- Diff: native read-only DiffRequestPanel, merge-base to source SHA; supports
  added/deleted files and explicitly reports missing revisions or binary content.
- Revision bar: labeled editable source/target refs, explicit Compare action,
  resolved SHA summary. Refresh never changes checkout or fetches code.

## 6. Motion & Interaction
Use native Swing focus, selection, buttons and progress states. No decorative
animation. Git work runs off EDT. Request generations discard stale selections;
closing the editor cancels its diff tasks and disposes native diff resources.
Review selection is separate from transport: HOME sends to WORK; WORK publishes
to GitLab. Editing a draft does not publish it.

## 7. Depth & Surface
Native panel backgrounds, splitter dividers and toolbar hierarchy. No custom
shadows or card elevation. Honor light/dark and the IDE UI scale.

## 8. Accessibility Constraints & Accepted Debt
Labeled revision fields, keyboard-operable lists/tabs, native focus indicators,
selectable body text and explicit empty/error states. Preserve existing transport
and publication gates. This first stage does not inject comments into the native
GitLab plugin, add editor gutter comments, or infer the SHA on which an older AI
draft was written. The SHA summary describes the local comparison only.
