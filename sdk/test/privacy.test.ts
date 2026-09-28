import { afterEach, describe, expect, it } from 'vitest';
import { isSensitive, maskInput, SENSITIVE_SELECTOR } from '../src/privacy';
import { startRecorder, type Recorder, type RrwebEvent } from '../src/recorder';

const SECRETS = {
  password: 'hunter2-PASSWORD-value',
  card: '4111111111111111',
  otp: '918273',
  otpByName: '564738',
  cvv: '8765',
  cvc: '4321',
  expiry: '12/29',
  blocked: 'SECRET-BLOCK-CONTENT',
  blockedInput: 'typed-in-blocked-area',
  plain: 'plain-input-value',
  textarea: 'initial-textarea-secret',
};

const FORM = `
  <p id="copy">Visible page copy</p>
  <input id="user" name="username">
  <input id="pw" type="password" data-si-unmask>
  <input id="card" autocomplete="cc-number" data-si-unmask>
  <input id="exp" autocomplete="billing cc-exp" data-si-unmask>
  <input id="code" autocomplete="one-time-code" data-si-unmask>
  <input id="x1" name="Login_OTP_code" data-si-unmask>
  <input id="CardCVV" data-si-unmask>
  <input id="z9" name="card-cvc" data-si-unmask>
  <div data-si-unmask><input id="nick"></div>
  <div data-si-block>${SECRETS.blocked}<input id="inblock"></div>
  <textarea id="notes">${SECRETS.textarea}</textarea>
`;

let recorder: Recorder | null = null;

afterEach(() => {
  recorder?.stop();
  recorder = null;
  document.body.innerHTML = '';
});

function type(id: string, value: string): void {
  const el = document.getElementById(id) as HTMLInputElement;
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

function record(maskAllText = false): RrwebEvent[] {
  const events: RrwebEvent[] = [];
  recorder = startRecorder({
    maskAllText,
    emit: (e) => events.push(e),
    onError: (e) => {
      throw e;
    },
  });
  expect(recorder).not.toBeNull();
  return events;
}

describe('sensitive field detection', () => {
  it.each([
    ['<input type="password">', true],
    ['<input type="PassWord">', true],
    ['<input autocomplete="current-password">', true],
    ['<input autocomplete="cc-number">', true],
    ['<input autocomplete="shipping cc-csc">', true],
    ['<input autocomplete="one-time-code">', true],
    ['<input name="otp">', true],
    ['<input id="SmsOtpInput">', true],
    ['<input name="cardCvv">', true],
    ['<input id="CVC2">', true],
    ['<textarea name="otp_backup"></textarea>', true],
    ['<select id="cvv-type"></select>', true],
    ['<input name="email">', false],
    ['<input autocomplete="email">', false],
    ['<div id="otp-help"></div>', false], // not a field
  ])('%s → %s', (html, expected) => {
    document.body.innerHTML = html;
    expect(isSensitive(document.body.firstElementChild)).toBe(expected);
  });

  it('builds a valid selector', () => {
    expect(() => document.querySelectorAll(SENSITIVE_SELECTOR)).not.toThrow();
  });
});

describe('maskInput', () => {
  it('masks by default, unmasks inside data-si-unmask, never for sensitive fields', () => {
    document.body.innerHTML = FORM;
    const el = (id: string) => document.getElementById(id);
    expect(maskInput('abc', el('user'))).toBe('***');
    expect(maskInput('abc', el('nick'))).toBe('abc');
    for (const id of ['pw', 'card', 'exp', 'code', 'x1', 'CardCVV', 'z9']) {
      expect(maskInput('1234', el(id))).toBe('****');
    }
  });

  it('keeps a password masked after a show-password toggle', () => {
    document.body.innerHTML = '<div data-si-unmask><input id="pw" type="password"></div>';
    const pw = document.getElementById('pw') as HTMLInputElement;
    expect(maskInput('secret', pw)).toBe('******');
    pw.type = 'text';
    expect(maskInput('secret', pw)).toBe('******');
  });
});

describe('recording (rrweb in jsdom)', () => {
  it('never records sensitive or blocked values, even with data-si-unmask', () => {
    document.body.innerHTML = FORM;
    // values present before recording starts (initial snapshot)
    (document.getElementById('pw') as HTMLInputElement).value = SECRETS.password;
    (document.getElementById('card') as HTMLInputElement).value = SECRETS.card;
    const events = record();

    // values typed after recording starts (incremental input events)
    type('pw', SECRETS.password + '!');
    type('card', SECRETS.card);
    type('exp', SECRETS.expiry);
    type('code', SECRETS.otp);
    type('x1', SECRETS.otpByName);
    type('CardCVV', SECRETS.cvv);
    type('z9', SECRETS.cvc);
    type('inblock', SECRETS.blockedInput);
    type('user', SECRETS.plain);
    type('nick', 'visible-nickname');
    // show-password toggle, then more typing
    (document.getElementById('pw') as HTMLInputElement).type = 'text';
    type('pw', SECRETS.password + '-after-toggle');

    const json = JSON.stringify(events);
    expect(events.length).toBeGreaterThan(2);
    for (const [name, secret] of Object.entries(SECRETS)) {
      expect(json, `${name} leaked`).not.toContain(secret);
    }
    expect(json).toContain('*'.repeat(SECRETS.plain.length)); // plain input: masked, not dropped
    expect(json).toContain('visible-nickname'); // explicit opt-in
    expect(json).toContain('Visible page copy'); // text is not masked by default
  });

  it('maskAllText masks all text nodes', () => {
    document.body.innerHTML = FORM;
    const json = JSON.stringify(record(true));
    expect(json).not.toContain('Visible page copy');
    expect(json).not.toContain(SECRETS.blocked);
  });

  it('does not record video', () => {
    document.body.innerHTML = '<video src="https://cdn.test/private.mp4"></video>';
    expect(JSON.stringify(record())).not.toContain('private.mp4');
  });
});
