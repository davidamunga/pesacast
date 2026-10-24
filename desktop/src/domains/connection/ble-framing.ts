/**
 * Reassembles BLE notifications into JSON payloads. See docs/protocol.md.
 *
 * Frames look like `<length>:<json>`, where length counts UTF-16 code units (JS `string.length`).
 * Payloads that don't start with a header are treated as a complete legacy message.
 */
export class FrameReassembler {
  private pending: { expected: number; data: string } | null = null;

  /** Returns a complete JSON string once one is available, otherwise null. */
  push(chunk: string): string | null {
    const colonIdx = chunk.indexOf(":");
    const maybeLen = colonIdx > 0 ? parseInt(chunk.slice(0, colonIdx), 10) : NaN;

    if (!isNaN(maybeLen)) {
      const payload = chunk.slice(colonIdx + 1);
      if (payload.length >= maybeLen) {
        this.pending = null;
        return payload.slice(0, maybeLen);
      }
      this.pending = { expected: maybeLen, data: payload };
      return null;
    }

    if (this.pending) {
      this.pending.data += chunk;
      if (this.pending.data.length >= this.pending.expected) {
        const json = this.pending.data.slice(0, this.pending.expected);
        this.pending = null;
        return json;
      }
      return null;
    }

    return chunk;
  }

  reset() {
    this.pending = null;
  }
}
