/**
 * Privacy rules (ADR-0006, FR-SDK-2). Sensitive fields are never recorded, whatever the
 * configuration: they are blocked (rrweb records a same-size placeholder, no value, no
 * events) and, as defence in depth, their values are masked even with `data-si-unmask`.
 */

/** Substrings of a field's `name` or `id` that mark it sensitive (case-insensitive). */
const SENSITIVE_NAME_TOKENS = ['otp', 'cvv', 'cvc'];
const FIELD_TAGS = ['input', 'textarea', 'select'];

export const SENSITIVE_SELECTOR = [
  'input[type="password" i]',
  '[autocomplete*="password" i]', // current-password, new-password
  '[autocomplete*="cc-" i]', // cc-number, cc-csc, cc-exp, … (also "billing cc-number")
  '[autocomplete~="one-time-code" i]',
  ...FIELD_TAGS.flatMap((tag) =>
    ['name', 'id'].flatMap((attr) => SENSITIVE_NAME_TOKENS.map((t) => `${tag}[${attr}*="${t}" i]`)),
  ),
].join(',');

/** Opt-out attribute for elements that must never be recorded. */
export const BLOCK_ATTRIBUTE = 'data-si-block';
/** Opt-in attribute: inputs inside it are recorded unmasked, unless sensitive. */
export const UNMASK_ATTRIBUTE = 'data-si-unmask';

/** Never recorded: opt-out elements, sensitive fields, and video (not recorded in v1). */
export const BLOCK_SELECTOR = [`[${BLOCK_ATTRIBUTE}]`, SENSITIVE_SELECTOR, 'video'].join(',');

/**
 * Elements ever seen as sensitive stay sensitive, so a "show password" toggle that changes
 * `type="password"` to `type="text"` cannot unmask the value.
 */
const onceSensitive = new WeakSet<Element>();

export function isSensitive(element: Element | null | undefined): boolean {
  if (!element) return false;
  if (onceSensitive.has(element)) return true;
  let sensitive: boolean;
  try {
    sensitive = element.matches(SENSITIVE_SELECTOR) || element.hasAttribute('data-rr-is-password');
  } catch {
    sensitive = true; // if in doubt, treat as sensitive
  }
  if (sensitive) onceSensitive.add(element);
  return sensitive;
}

/** Remembers the sensitive fields currently in the document (called at record start). */
export function rememberSensitiveFields(root: ParentNode): void {
  try {
    root.querySelectorAll(SENSITIVE_SELECTOR).forEach((el) => onceSensitive.add(el));
  } catch {
    // ignore
  }
}

export function mask(text: string): string {
  return '*'.repeat(text.length);
}

/**
 * rrweb `maskInputFn`: called for every input value rrweb records (all inputs are masked
 * by default). Returns the value unmasked only inside `[data-si-unmask]`, never for a
 * sensitive field.
 */
export function maskInput(text: string, element: HTMLElement | null | undefined): string {
  if (!element || isSensitive(element)) return mask(text);
  try {
    if (element.closest(`[${UNMASK_ATTRIBUTE}]`)) return text;
  } catch {
    // fall through to masked
  }
  return mask(text);
}

/** True if the element is blocked or inside a blocked element. */
export function isBlocked(element: Element): boolean {
  try {
    return element.closest(BLOCK_SELECTOR) !== null || isSensitive(element);
  } catch {
    return true;
  }
}

/** Text never read for click labels, besides blocked elements. */
const SKIPPED_TEXT_PARENTS = 'script,style,noscript,textarea,select,option,template';

/**
 * Visible text of a clicked element, for the CLICK event's `targetText`. Never taken from
 * form fields or blocked/sensitive elements (including blocked descendants); masked like
 * page text when `maskAllText` is on. At most `maxLength` characters.
 */
export function clickText(
  element: Element,
  maskAllText: boolean,
  maxLength: number,
): string | undefined {
  if (isBlocked(element) || element.closest('input,textarea,select')) return undefined;
  let text = '';
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node && text.length <= maxLength; node = walker.nextNode()) {
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIPPED_TEXT_PARENTS) || isBlocked(parent)) continue;
    text += `${node.nodeValue ?? ''} `;
  }
  text = text.replace(/\s+/g, ' ').trim().slice(0, maxLength).trim();
  if (!text) return undefined;
  return maskAllText ? text.replace(/\S/g, '*') : text;
}
