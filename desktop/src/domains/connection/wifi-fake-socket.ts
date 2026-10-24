// Test double for the phone's side of the WiFi v2 handshake, using real WebCrypto. Shared by the
// useWifi tests; not imported by the app.
import { act } from "@testing-library/react";
import { expect, vi } from "vitest";
import type { useWifi } from "./useWifi";
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
export class FakeWebSocket {
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

export const last = () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1];

export async function connectSecure(result: { current: ReturnType<typeof useWifi> }, token = "tok") {
  await act(async () => {
    await result.current.connectWifi("192.168.0.14", 7877, token);
  });
  await act(async () => {
    last().serverOpen();
  });
  await vi.waitFor(() => expect(last().sent).toHaveLength(1));
  await last().completeHello();
}


/** Connects and completes the whole handshake, ending in the "connected" state. */
export async function connected(result: { current: ReturnType<typeof useWifi> }) {
  await connectSecure(result);
  await vi.waitFor(() => expect(last().sent).toHaveLength(2));
  await last().serverSendsRecord('{"status":"ok"}');
}
