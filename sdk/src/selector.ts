import { FIELD_LIMITS, truncate } from './wire';

const MAX_DEPTH = 5;

/**
 * A short CSS selector for a clicked element: up to five levels of
 * `tag#id` / `tag.class.class:nth-of-type(n)`, stopping at the first id.
 */
export function cssSelector(element: Element): string {
  const parts: string[] = [];
  let el: Element | null = element;
  while (el && el.nodeType === 1 && parts.length < MAX_DEPTH) {
    const tag = el.tagName.toLowerCase();
    if (tag === 'html' || tag === 'body') {
      parts.unshift(tag);
      break;
    }
    if (el.id) {
      parts.unshift(`${tag}#${escape(el.id)}`);
      break;
    }
    let part = tag;
    const classes = Array.from(el.classList).slice(0, 2);
    if (classes.length) part += classes.map((c) => `.${escape(c)}`).join('');
    const parent: Element | null = el.parentElement;
    if (parent) {
      const sameTag = Array.from(parent.children).filter((c) => c.tagName === el!.tagName);
      if (sameTag.length > 1) part += `:nth-of-type(${sameTag.indexOf(el) + 1})`;
    }
    parts.unshift(part);
    el = parent;
  }
  return truncate(parts.join(' > '), FIELD_LIMITS.targetSelector);
}

function escape(ident: string): string {
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(ident);
  return ident.replace(/[^a-zA-Z0-9_-]/g, (ch) => `\\${ch}`);
}
