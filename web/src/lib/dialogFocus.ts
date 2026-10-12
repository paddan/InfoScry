import { tick } from 'svelte';

/**
 * Keep keyboard focus inside an open modal confirmation. The dialog is announced as modal, so Tab must
 * not walk into the page behind the backdrop.
 */
export function containDialogFocus(event: KeyboardEvent, dialog: HTMLElement | null): void {
  if (dialog === null) return;
  const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  ));
  if (focusable.length === 0) {
    // A submitted removal disables every control inside, so there is nothing for Tab to move to: the
    // dialog itself takes the key and keeps it, rather than letting go of it into the page behind.
    event.preventDefault();
    dialog.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement;
  if (active === first && event.shiftKey) {
    event.preventDefault();
    last.focus();
  } else if (active === last && !event.shiftKey) {
    event.preventDefault();
    first.focus();
  } else if (active === dialog || !dialog.contains(active)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  }
}

/**
 * A submission disables both dialog controls, and a disabled control cannot hold focus in a browser,
 * so the dialog itself — which stays focusable — takes it once the flush has disabled them. The wait
 * is not awaited by the submission: it only serves the keyboard, never the request.
 */
export function holdDialogFocus(dialog: HTMLElement | null): void {
  void tick().then(() => dialog?.focus());
}
