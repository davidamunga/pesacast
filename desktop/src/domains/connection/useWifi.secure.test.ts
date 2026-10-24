// @vitest-environment happy-dom
import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));

import { useWifi } from "./useWifi";
import {
  DIR_CLIENT_TO_SERVER,
  DIR_SERVER_TO_CLIENT,
  b64ToBytes,
  bytesToB64,
  openRecord,
  sealRecord,
} from "./wifi-crypto";

const enc = new TextEncoder();

/** Plays the phone's side of the v2 handshake with real crypto. */
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  binaryType = "blob";
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string | ArrayBuffer }) => Promise<void> | void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  sent: (string | Uint8Array)[] = [];
  closed = false;

  // Server-side session state
  private token = "tok";
  private c2s = new Uint8Array();
  private s2c = new Uint8Array();
  private sendCounter = 0n;
  private receiveCounter = 0n;

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }
  send(data: string | Uint8Array) {
    this.sent.push(data);
  }
  close() {
    if (this.closed) return;
    this.closed = true;
    this.onclose?.();
  }
  serverOpen() {
    this.onopen?.();
  }
  serverDrops() {
    this.closed = true;
    this.onclose?.();
  }
  private async deliver(data: string | ArrayBuffer) {
    await act(async () => {
      await this.onmessage?.({ data });
    });
  }
  async serverSaysText(text: string) {
    await this.deliver(text);
  }
  async serverSendsRecord(plaintext: string, counter = this.sendCounter++) {
    const rec = await sealRecord(this.s2c, DIR_SERVER_TO_CLIENT, counter, plaintext);
    await this.deliver(rec.buffer.slice(rec.byteOffset, rec.byteOffset + rec.byteLength) as ArrayBuffer);
  }

  /** Reads the client's hello, replies with a server hello and derives the session keys. */
  async completeHello(token = this.token) {
    this.token = token;
    const hello = JSON.parse(this.sent[0] as string) as { hello: { pub: string; nonce: string } };
    const clientPub = b64ToBytes(hello.hello.pub);
    const clientNonce = b64ToBytes(hello.hello.nonce);

    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    const serverPub = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
    const serverNonce = crypto.getRandomValues(new Uint8Array(16));
    const peer = await crypto.subtle.importKey("raw", clientPub, { name: "ECDH", namedCurve: "P-256" }, false, []);
    const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: peer }, pair.privateKey, 256);
    const info = new Uint8Array([...enc.encode("pesacast-v2"), ...clientPub, ...serverPub, ...clientNonce, ...serverNonce]);
    const hk = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]);
    const okm = new Uint8Array(
      await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: enc.encode(token), info }, hk, 512),
    );
    this.c2s = okm.slice(0, 32);
    this.s2c = okm.slice(32);

    await this.serverSaysText(
      JSON.stringify({ v: 2, hello: { pub: bytesToB64(serverPub), nonce: bytesToB64(serverNonce) } }),
    );
  }

  /** Decrypts the client's n-th binary frame. */
  async clientRecord(): Promise<string | null> {
    const binaries = this.sent.filter((s): s is Uint8Array => typeof s !== "string");
    const rec = binaries[Number(this.receiveCounter)];
    if (!rec) return null;
    const plain = await openRecord(this.c2s, DIR_CLIENT_TO_SERVER, this.receiveCounter, rec);
    if (plain !== null) this.receiveCounter++;
    return plain;
  }
}

const last = () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1];

async function connectSecure(result: { current: ReturnType<typeof useWifi> }, token = "tok") {
  await act(async () => {
    await result.current.connectWifi("192.168.0.14", 7877, token);
  });
  await act(async () => {
    last().serverOpen();
  });
  await vi.waitFor(() => expect(last().sent).toHaveLength(1));
  await last().completeHello();
}

describe("useWifi (encrypted v2 protocol)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval", "Date"] });
    FakeWebSocket.instances = [];
    invoke.mockReset();
    invoke.mockResolvedValue(undefined);
    localStorage.clear();
    vi.stubGlobal("WebSocket", FakeWebSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("sends a v2 hello, proves the token with an encrypted ready record, and connects on ok", async () => {
    const { result } = renderHook(() => useWifi());
    await connectSecure(result);

    expect(last().binaryType).toBe("arraybuffer");
    expect(JSON.parse(last().sent[0] as string).v).toBe(2);
    expect(JSON.stringify(last().sent[0])).not.toContain("tok"); // the token is never sent
    expect(result.current.wifiStatus).toBe("connecting");

    await vi.waitFor(() => expect(last().sent).toHaveLength(2));
    expect(await last().clientRecord()).toBe('{"ready":true}');

    await last().serverSendsRecord('{"status":"ok"}');
    expect(result.current.wifiStatus).toBe("connected");
    expect(result.current.wifiSecure).toBe(true);
  });

  it("forwards decrypted transactions to the backend", async () => {
    const { result } = renderHook(() => useWifi());
    await connectSecure(result);
    await vi.waitFor(() => expect(last().sent).toHaveLength(2));
    await last().serverSendsRecord('{"status":"ok"}');
    await last().serverSendsRecord('{"ref":"ABC"}');
    expect(invoke).toHaveBeenCalledWith("process_wifi_transaction", { txnJson: '{"ref":"ABC"}' });
  });

  it("reports a wrong token when the phone hangs up after the ready record, and does not retry", async () => {
    const { result } = renderHook(() => useWifi());
    await connectSecure(result);
    await vi.waitFor(() => expect(last().sent).toHaveLength(2));
    act(() => last().serverDrops());

    expect(result.current.wifiStatus).toBe("error");
    expect(result.current.wifiError).toMatch(/authentication failed/i);
    act(() => vi.advanceTimersByTime(120_000));
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("reports a wrong token when the phone's reply cannot be decrypted", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifi("192.168.0.14", 7877, "tok");
    });
    await act(async () => {
      last().serverOpen();
    });
    await vi.waitFor(() => expect(last().sent).toHaveLength(1));
    await last().completeHello("a-different-token");
    await vi.waitFor(() => expect(last().sent).toHaveLength(2));
    await last().serverSendsRecord('{"status":"ok"}');

    expect(result.current.wifiStatus).toBe("error");
    expect(result.current.wifiError).toMatch(/authentication failed/i);
    expect(invoke).not.toHaveBeenCalled();
  });

  it("offers an explicit unencrypted fallback when an older phone hangs up on the hello", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifi("192.168.0.14", 7877, "tok");
    });
    await act(async () => {
      last().serverOpen();
    });
    await vi.waitFor(() => expect(last().sent).toHaveLength(1));
    act(() => last().serverDrops());

    expect(result.current.wifiStatus).toBe("error");
    expect(result.current.canConnectLegacy).toBe(true);
    expect(result.current.wifiError).toMatch(/encrypted/i);
    // Never downgrades by itself.
    act(() => vi.advanceTimersByTime(120_000));
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("does not offer the fallback when the phone was unreachable", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifi("192.168.0.14", 7877, "tok");
    });
    act(() => {
      last().onerror?.();
      last().serverDrops();
    });
    expect(result.current.canConnectLegacy).toBe(false);
  });

  it("connects unencrypted only through connectWifiLegacy, and marks it as such", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifiLegacy("192.168.0.14", 7877, "tok");
    });
    await act(async () => {
      last().serverOpen();
    });
    expect(last().sent).toEqual([JSON.stringify({ auth: "tok" })]);
    await last().serverSaysText('{"status":"ok"}');
    expect(result.current.wifiStatus).toBe("connected");
    expect(result.current.wifiSecure).toBe(false);
  });

  it("explains how to proceed when the phone refuses an unencrypted connection", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifiLegacy("192.168.0.14", 7877, "tok");
    });
    await act(async () => {
      last().serverOpen();
    });
    act(() => last().serverDrops());

    expect(result.current.wifiStatus).toBe("error");
    expect(result.current.wifiError).toMatch(/allow unencrypted connections/i);
    act(() => vi.advanceTimersByTime(120_000));
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("rejects a malformed server hello", async () => {
    const { result } = renderHook(() => useWifi());
    await act(async () => {
      await result.current.connectWifi("192.168.0.14", 7877, "tok");
    });
    await act(async () => {
      last().serverOpen();
    });
    await vi.waitFor(() => expect(last().sent).toHaveLength(1));
    await last().serverSaysText('{"status":"ok"}');
    expect(result.current.wifiStatus).toBe("error");
    expect(result.current.wifiSecure).toBe(false);
  });

  it("reconnects an established encrypted session with a fresh handshake", async () => {
    const { result } = renderHook(() => useWifi());
    await connectSecure(result);
    await vi.waitFor(() => expect(last().sent).toHaveLength(2));
    await last().serverSendsRecord('{"status":"ok"}');
    expect(result.current.wifiStatus).toBe("connected");

    act(() => last().serverDrops());
    expect(result.current.wifiStatus).toBe("connecting");
    await act(async () => {
      vi.advanceTimersByTime(2_001);
    });
    expect(FakeWebSocket.instances).toHaveLength(2);
    await act(async () => {
      last().serverOpen();
    });
    await vi.waitFor(() => expect(last().sent).toHaveLength(1));
    expect(JSON.parse(last().sent[0] as string).v).toBe(2);
  });
});
