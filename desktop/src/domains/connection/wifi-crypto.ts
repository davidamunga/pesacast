// WiFi v2 secure channel (docs/protocol.md, "WiFi v2"): ephemeral P-256 ECDH, HKDF-SHA256 keyed by
// the pairing token, AES-256-GCM records with counter nonces. Uses only WebCrypto.

const INFO_PREFIX = "pesacast-v2";
const NONCE_SIZE = 16;
const POINT_SIZE = 65;
const TAG_BYTES = 16;
export const DIR_CLIENT_TO_SERVER = 0x01;
export const DIR_SERVER_TO_CLIENT = 0x02;

const subtle = () => globalThis.crypto.subtle;
const utf8 = new TextEncoder();
const utf8d = new TextDecoder();

export function bytesToB64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function b64ToBytes(b64: string): Uint8Array {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

export interface ClientHello {
  privateKey: CryptoKey;
  publicKey: Uint8Array; // 65-byte uncompressed point
  nonce: Uint8Array; // 16 bytes
}

export async function createClientHello(): Promise<ClientHello> {
  const pair = await subtle().generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const publicKey = new Uint8Array(await subtle().exportKey("raw", pair.publicKey));
  const nonce = globalThis.crypto.getRandomValues(new Uint8Array(NONCE_SIZE));
  return { privateKey: pair.privateKey, publicKey, nonce };
}

export function helloMessage(hello: ClientHello): string {
  return JSON.stringify({
    v: 2,
    hello: { pub: bytesToB64(hello.publicKey), nonce: bytesToB64(hello.nonce) },
  });
}

/** Parses the server hello text frame. Returns null if it is not a valid v2 hello. */
export function parseServerHello(text: string): { publicKey: Uint8Array; nonce: Uint8Array } | null {
  try {
    const msg = JSON.parse(text) as { v?: number; hello?: { pub?: string; nonce?: string } };
    if (msg.v !== 2 || !msg.hello?.pub || !msg.hello.nonce) return null;
    const publicKey = b64ToBytes(msg.hello.pub);
    const nonce = b64ToBytes(msg.hello.nonce);
    if (publicKey.length !== POINT_SIZE || nonce.length !== NONCE_SIZE) return null;
    return { publicKey, nonce };
  } catch {
    return null;
  }
}

export interface DerivedKeys {
  sharedSecret: Uint8Array;
  clientToServer: Uint8Array;
  serverToClient: Uint8Array;
}

/** ECDH + HKDF. `privateKey` is the client's ephemeral key; throws if the server key is invalid. */
export async function deriveKeys(
  privateKey: CryptoKey,
  clientPub: Uint8Array,
  clientNonce: Uint8Array,
  serverPub: Uint8Array,
  serverNonce: Uint8Array,
  token: string,
): Promise<DerivedKeys> {
  // importKey validates that the point is on the curve.
  const peer = await subtle().importKey("raw", serverPub, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const shared = new Uint8Array(await subtle().deriveBits({ name: "ECDH", public: peer }, privateKey, 256));
  const info = concat(utf8.encode(INFO_PREFIX), clientPub, serverPub, clientNonce, serverNonce);
  const hkdfKey = await subtle().importKey("raw", shared, "HKDF", false, ["deriveBits"]);
  const okm = new Uint8Array(
    await subtle().deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: utf8.encode(token), info },
      hkdfKey,
      512,
    ),
  );
  return { sharedSecret: shared, clientToServer: okm.slice(0, 32), serverToClient: okm.slice(32, 64) };
}

function nonceFor(counter: bigint): Uint8Array {
  const out = new Uint8Array(12);
  new DataView(out.buffer).setBigUint64(4, counter);
  return out;
}

function aadFor(direction: number, counter: bigint): Uint8Array {
  const out = new Uint8Array(9);
  out[0] = direction;
  new DataView(out.buffer).setBigUint64(1, counter);
  return out;
}

export async function sealRecord(
  key: Uint8Array,
  direction: number,
  counter: bigint,
  plaintext: string,
): Promise<Uint8Array> {
  const aesKey = await subtle().importKey("raw", key, "AES-GCM", false, ["encrypt"]);
  const ct = new Uint8Array(
    await subtle().encrypt(
      { name: "AES-GCM", iv: nonceFor(counter), additionalData: aadFor(direction, counter), tagLength: 128 },
      aesKey,
      utf8.encode(plaintext),
    ),
  );
  const header = new Uint8Array(8);
  new DataView(header.buffer).setBigUint64(0, counter);
  return concat(header, ct);
}

/** Returns the plaintext, or null if the counter is unexpected or authentication fails. */
export async function openRecord(
  key: Uint8Array,
  direction: number,
  expectedCounter: bigint,
  record: Uint8Array,
): Promise<string | null> {
  if (record.length < 8 + TAG_BYTES) return null;
  const counter = new DataView(record.buffer, record.byteOffset, 8).getBigUint64(0);
  if (counter !== expectedCounter) return null;
  try {
    const aesKey = await subtle().importKey("raw", key, "AES-GCM", false, ["decrypt"]);
    const plain = await subtle().decrypt(
      { name: "AES-GCM", iv: nonceFor(counter), additionalData: aadFor(direction, counter), tagLength: 128 },
      aesKey,
      record.slice(8),
    );
    return utf8d.decode(plain);
  } catch {
    return null;
  }
}

/** Client side of an established channel: tracks both counters. */
export class SecureChannel {
  private sendCounter = 0n;
  private receiveCounter = 0n;

  constructor(
    private readonly sendKey: Uint8Array,
    private readonly receiveKey: Uint8Array,
  ) {}

  async seal(plaintext: string): Promise<Uint8Array> {
    return sealRecord(this.sendKey, DIR_CLIENT_TO_SERVER, this.sendCounter++, plaintext);
  }

  /** Records must be opened in arrival order; WebSocket delivers them in order. */
  async open(record: Uint8Array): Promise<string | null> {
    const plain = await openRecord(this.receiveKey, DIR_SERVER_TO_CLIENT, this.receiveCounter, record);
    if (plain !== null) this.receiveCounter++;
    return plain;
  }
}
