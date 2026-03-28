---
name: dashboard-a11y
description: Accessibility and component guidelines for the ClojureScript dashboard. Use when writing, editing, or reviewing any .cljs file under src/memlayer/dashboard/. Not for backend Clojure, config, or documentation.
user-invocable: false
---

Follow these guidelines when editing dashboard ClojureScript files to maintain WCAG 2.1 AA accessibility and consistent component usage.

## Form Controls

- **Always associate labels with inputs.** Use `ui/form-group` or explicit `:html-for` / `:id` pairs.
- Never render a `[:label]` next to a form control without `:html-for` pointing to the control's `:id`.
- Pass `:id` to `ui/input`, `ui/textarea`, `ui/select` when a visible label exists.
- Use `:aria-label` as a fallback only when a visible label is not possible (e.g., icon-only search).

### form-group usage

```clojure
[ui/form-group {:label "Email" :id "email-input" :required? true}
  [ui/input {:id "email-input" :value v :on-change f}]]
```

The caller must pass the same `:id` to both `form-group` and the child control.

## ARIA Patterns

### Tabs

```clojure
[:div {:role "tablist" :aria-label "Section name"}
  [:button {:role "tab" :aria-selected (= tab active)} "Tab 1"]]
[:div {:role "tabpanel"} content]
```

### Toggle buttons

Use `:aria-pressed` for on/off toggles (e.g., layer visibility, range selectors):

```clojure
[:button {:aria-pressed active? :on-click toggle!} "Label"]
```

### Expandable sections

```clojure
[:button {:aria-expanded open? :aria-controls "panel-id"} "Toggle"]
[:div {:id "panel-id"} content]
```

### Progress bars

```clojure
[:div {:role "progressbar" :aria-valuenow pct :aria-valuemin 0 :aria-valuemax 100
       :aria-label "Upload progress"}
  [:div {:style {:width (str pct "%")}}]]
```

### Alerts

`ui/alert` automatically sets `role="alert"` and appropriate `aria-live`. No extra work needed.

### Modals

`ui/modal` handles `role="dialog"`, `aria-modal`, `aria-labelledby`, focus trap, and Escape dismissal. No extra work needed from callers.

### Loading spinners

`ui/loading-spinner` and `loading/spinner` include `role="status"` and `sr-only` text. No extra work needed.

## Tables

- Always add `:scope "col"` to `<th>` elements.
- Clickable rows must have `:tab-index 0`, `:role "link"` (or `:role "button"`), and an `:on-key-down` handler for Enter/Space.

```clojure
[:tr {:tab-index 0 :role "link"
      :on-click #(open! item)
      :on-key-down (fn [e]
                     (when (contains? #{"Enter" " "} (.-key e))
                       (.preventDefault e)
                       (open! item)))}]
```

## Icons and Decorative Elements

- Decorative SVG icons next to text labels: add `:aria-hidden "true"`.
- Standalone icon buttons (no visible text): must have `:aria-label`.
- Color indicator dots (status badges conveying only color): add `:aria-hidden "true"` when text conveys the same information.
- Decorative step numbers: add `:aria-hidden "true"`.

## Navigation

- Active nav links: add `:aria-current "page"`.
- Nav landmarks: wrap in `[:nav {:aria-label "Main navigation"}]`.

## Reagent / Hiccup Notes

- Use `:html-for` (not `:for`) for label association.
- Use `:tab-index` (not `:tabIndex`) for tab index.
- Use `:auto-focus` (not `:autoFocus`).
- ARIA attributes use kebab-case: `:aria-label`, `:aria-selected`, `:aria-expanded`, `:aria-pressed`, etc.

## Tailwind A11y Classes

- `sr-only` — visually hidden, readable by screen readers.
- Focus rings: `focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500` on all interactive elements.
- All interactive elements must be keyboard-reachable (Tab) and activatable (Enter/Space).

## Consistency

Use these spacing/styling patterns to match the existing design system:

| Element       | Classes                          |
|--------------|----------------------------------|
| Card padding  | `p-6`                           |
| Form controls | `px-3 py-2`                     |
| Table cells   | `px-4 py-3`                     |
| Form labels   | `text-sm font-medium text-gray-700 dark:text-gray-300` |
| Page headings | `text-2xl font-bold text-gray-900 dark:text-gray-100` |
| Section gaps  | `space-y-6` (page), `space-y-4` (within cards) |
| Button base   | Use `ui/button` with `:variant` — never hand-roll button styles |
| Inputs        | Use `ui/input` / `ui/textarea` / `ui/select` — never hand-roll input styles |
