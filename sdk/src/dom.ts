/** Adds a listener and returns the function that removes it. */
export function listen(
  target: EventTarget,
  type: string,
  handler: (event: Event) => void,
  options: AddEventListenerOptions = {},
): () => void {
  target.addEventListener(type, handler, options);
  return () => target.removeEventListener(type, handler, options);
}
