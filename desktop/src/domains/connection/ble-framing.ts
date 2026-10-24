/**
 * Reassembles BLE notifications into JSON payloads. See docs/protocol.md.
 *
 * Frames look like `<length>:<json>`, where length counts UTF-16 code units (JS `string.length`).
 * Payloads that don't start with a header are treated as a complete legacy message.
 */
/** A partial frame older than this is abandoned, so one lost chunk can't swallow later messages. */
const STALE_AFTER_MS = 5000;

export class FrameReassembler {
  private pending: { expected: number; data: string; updatedAt: number } | null = null;

  constructor(private readonly now: () => number = Date.now) {}

  /** Returns a complete JSON string once one is available, otherwise null. */
  push(chunk: string): string | null {
    const now = this.now();
    if (this.pending && now - this.pending.updatedAt > STALE_AFTER_MS) {
      this.pending = null;
    }

    // While a frame is in progress every chunk is data, even one that starts like a header
    // (e.g. "12:12:00+03:00" inside an ISO timestamp).
    if (this.pending) {
      this.pending.data += chunk;
      this.pending.updatedAt = now;
      if (this.pending.data.length >= this.pending.expected) {
        const json = this.pending.data.slice(0, this.pending.expected);
        this.pending = null;
        return json;
      }
      return null;
    }

    const header = /^(\d+):/.exec(chunk);
    if (header) {
      const expected = parseInt(header[1], 10);
      const payload = chunk.slice(header[0].length);
      if (payload.length >= expected) return payload.slice(0, expected);
      this.pending = { expected, data: payload, updatedAt: now };
      return null;
    }

    return chunk;
  }

  reset() {
    this.pending = null;
  }
}
