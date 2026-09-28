/**
 * Privacy rules (ADR-0006, FR-SDK-2). Sensitive fields are never recorded, whatever the
 * configuration. Fields matched by CSS (password type, password / card / one-time-code
 * autocomplete) are blocked: rrweb records a same-size placeholder, no value, no events.
 * Fields matched by name/id tokens (otp, cvv, …) cannot be expressed in CSS, so they are
 * handled in JavaScript only, with no writes to the host page: their values always record
 * as a constant-length mask, so neither the value nor its length is recorded, and they are
 * never read for click text. Either way, `data-si-unmask` never reveals them.
 */

/** Name/id tokens that mark a form field sensitive (compared case-insensitively). */
const SENSITIVE_NAME_TOKENS = new Set(['otp', 'cvv', 'cvc', 'cvn']);
const FIELD_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

/** What any non-empty sensitive value records as: constant, so its length is not leaked. */
export const SENSITIVE_MASK = '*'.repeat(6);

/** Sensitive by CSS alone; also rrweb's block selector (see BLOCK_SELECTOR). */
export const SENSITIVE_SELECTOR = [
  'input[type="password" i]',
  '[autocomplete*="password" i]', // current-password, new-password
  '[autocomplete*="cc-" i]', // cc-number, cc-csc, cc-exp, … (also "billing cc-number")
  '[autocomplete~="one-time-code" i]',
].join(',');

/**
 * Splits a `name`/`id` into lower-case tokens at non-alphanumerics, camelCase and acronym
 * boundaries, and between letters and digits: `otpCode` → otp code, `OTPCode` → otp code,
 * `card_cvv` → card cvv, `CVC2` → cvc 2, `footprint` → footprint.
 */
export function nameTokens(value: string): string[] {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .replace(/([a-zA-Z])([0-9])/g, '$1 $2')
    .replace(/([0-9])([a-zA-Z])/g, '$1 $2')
    .split(/[^a-zA-Z0-9]+/)
    .filter((t) => t.length > 0)
    .map((t) => t.toLowerCase());
}

/** A form field whose `name` or `id` has a sensitive token. */
function hasSensitiveName(element: Element): boolean {
  if (!FIELD_TAGS.has(element.tagName.toUpperCase())) return false;
  for (const attr of ['name', 'id']) {
    const value = element.getAttribute(attr);
    if (value && nameTokens(value).some((t) => SENSITIVE_NAME_TOKENS.has(t))) return true;
  }
  return false;
}

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
    sensitive =
      element.matches(SENSITIVE_SELECTOR) ||
      element.hasAttribute('data-rr-is-password') ||
      hasSensitiveName(element);
  } catch {
    sensitive = true; // if in doubt, treat as sensitive
  }
  if (sensitive) onceSensitive.add(element);
  return sensitive;
}

/**
 * Remembers the sensitive fields currently in the document (called at record start), so a
 * later rename or type change cannot make them recordable. In memory only: nothing is
 * written to the page.
 */
export function rememberSensitiveFields(root: ParentNode): void {
  try {
    root.querySelectorAll('input,textarea,select').forEach((el) => isSensitive(el));
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
 * sensitive field; a sensitive field's value is always {@link SENSITIVE_MASK}, whatever its
 * length (an empty value stays empty). An unknown element counts as sensitive.
 */
export function maskInput(text: string, element: HTMLElement | null | undefined): string {
  if (!element || isSensitive(element)) return text.length === 0 ? '' : SENSITIVE_MASK;
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
