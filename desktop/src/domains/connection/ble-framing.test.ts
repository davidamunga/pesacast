import { describe, expect, it } from "vitest";
import { FrameReassembler } from "./ble-framing";

const frame = (json: string) => `${json.length}:${json}`;

/** Splits on UTF-16 units; the Android side splits on characters, never inside one. */
const split = (s: string, size: number) => {
  const out: string[] = [];
  for (let i = 0; i < s.length; i += size) out.push(s.slice(i, i + size));
  return out;
};

const feed = (r: FrameReassembler, chunks: string[]) =>
  chunks.map((c) => r.push(c)).filter((x): x is string => x !== null);

describe("FrameReassembler", () => {
  it("returns a single-chunk frame immediately", () => {
    const r = new FrameReassembler();
    expect(r.push(frame('{"a":1}'))).toBe('{"a":1}');
  });

  it("reassembles a multi-chunk frame", () => {
    const json = '{"type":"mpesa_txn","direction":"received","amount":500,"from":"JOHN DOE"}';
    const out = feed(new FrameReassembler(), split(frame(json), 17));
    expect(out).toEqual([json]);
  });

  it("drops anything past the declared length", () => {
    const r = new FrameReassembler();
    expect(r.push('3:abcdef')).toBe("abc");
  });

  it("handles back-to-back frames", () => {
    const r = new FrameReassembler();
    const a = '{"n":1}';
    const b = '{"n":22}';
    const out = feed(r, [...split(frame(a), 5), ...split(frame(b), 5)]);
    expect(out).toEqual([a, b]);
  });

  it("passes through unframed legacy payloads", () => {
    const r = new FrameReassembler();
    expect(r.push('{"legacy":true}')).toBe('{"legacy":true}');
  });

  it("counts UTF-16 units, so accents and emoji reassemble", () => {
    const json = '{"from":"José Ñandú 😀"}';
    const out = feed(new FrameReassembler(), split(frame(json), 7));
    expect(out).toEqual([json]);
  });

  it("reset discards a partial frame", () => {
    const r = new FrameReassembler();
    expect(r.push("10:abc")).toBeNull();
    r.reset();
    expect(r.push(frame('{"a":1}'))).toBe('{"a":1}');
  });

  it("treats a continuation chunk that looks like a header as data", () => {
    // The chunk boundary lands right before "12:12", which parses as "length 12, then payload".
    const json = '{"time":"2026-02-27T12:12:00+03:00"}';
    const framed = frame(json);
    const idx = framed.indexOf("12:12");
    const out = feed(new FrameReassembler(), [framed.slice(0, idx), framed.slice(idx)]);
    expect(out).toEqual([json]);
  });

  it("starts a new frame once a partial one has gone stale", () => {
    let now = 0;
    const r = new FrameReassembler(() => now);
    expect(r.push("50:abc")).toBeNull(); // the rest of this frame is lost
    now = 10_000;
    expect(r.push(frame('{"a":1}'))).toBe('{"a":1}');
  });
});
