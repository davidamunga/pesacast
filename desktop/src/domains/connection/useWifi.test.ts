// @vitest-environment happy-dom
import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));

import { useWifi } from "./useWifi";
import { FakeWebSocket, connected, connectSecure, last } from "./wifi-fake-socket";

type Hook = { current: ReturnType<typeof useWifi> };

async function connect(result: Hook, ip = "192.168.0.14", port = 7877, token = "tok") {
  await act(async () => {
    await result.current.connectWifi(ip, port, token);
  });
}

/** Opens the current socket and runs the handshake through to the phone's "ok" record. */
async function openAndAuthenticate(token = "tok") {
  await act(async () => {
    last().serverOpen();
  });
  await vi.waitFor(() => expect(last().sent).toHaveLength(1));
  await last().completeHello(token);
  await vi.waitFor(() => expect(last().sent).toHaveLength(2));
  await last().serverSendsRecord('{"status":"ok"}');
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

  describe("connecting", () => {
    it("sends a v2 hello, proves the token with an encrypted ready record, and connects on ok", async () => {
      const { result } = renderHook(() => useWifi());
      await connectSecure(result);

      expect(last().url).toBe("ws://192.168.0.14:7877");
      expect(last().binaryType).toBe("arraybuffer");
      expect(JSON.parse(last().sent[0] as string).v).toBe(2);
      expect(JSON.stringify(last().sent[0])).not.toContain("tok"); // the token is never sent
      expect(result.current.wifiStatus).toBe("connecting");

      await vi.waitFor(() => expect(last().sent).toHaveLength(2));
      expect(await last().clientRecord()).toBe('{"ready":true}');

      await last().serverSendsRecord('{"status":"ok"}');
      expect(result.current.wifiStatus).toBe("connected");
    });

    it("trims input, persists it, and falls back to the default port when invalid", async () => {
      const { result } = renderHook(() => useWifi());
      await connect(result, "  192.168.0.14 ", Number.NaN, " tok ");
      expect(last().url).toBe("ws://192.168.0.14:7877");
      expect(localStorage.getItem("wifi_ip")).toBe("192.168.0.14");
      expect(localStorage.getItem("wifi_port")).toBe("7877");
      expect(localStorage.getItem("wifi_token")).toBe("tok");
    });

    it.each([0, -1, 65536, 1.5])("rejects out-of-range port %s", async (port) => {
      const { result } = renderHook(() => useWifi());
      await connect(result, "10.0.0.2", port);
      expect(last().url).toBe("ws://10.0.0.2:7877");
    });

    it("forwards decrypted transactions to the backend", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);
      await last().serverSendsRecord('{"ref":"ABC"}');
      expect(invoke).toHaveBeenCalledWith("process_wifi_transaction", { txnJson: '{"ref":"ABC"}' });
    });

    it("does not forward anything before auth completes", async () => {
      const { result } = renderHook(() => useWifi());
      await connectSecure(result);
      await vi.waitFor(() => expect(last().sent).toHaveLength(2));
      await last().serverSendsRecord('{"status":"denied"}');
      expect(result.current.wifiStatus).toBe("error");
      expect(invoke).not.toHaveBeenCalled();
    });

    it("rejects a malformed server hello", async () => {
      const { result } = renderHook(() => useWifi());
      await connect(result);
      await act(async () => {
        last().serverOpen();
      });
      await vi.waitFor(() => expect(last().sent).toHaveLength(1));
      await last().serverSaysText('{"status":"ok"}');
      expect(result.current.wifiStatus).toBe("error");
    });
  });

  describe("failures", () => {
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
      await connect(result, "192.168.0.14", 7877, "tok");
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

    it("tells the user to update an older phone that hangs up on the hello, and does not retry", async () => {
      const { result } = renderHook(() => useWifi());
      await connect(result);
      await act(async () => {
        last().serverOpen();
      });
      await vi.waitFor(() => expect(last().sent).toHaveLength(1));
      act(() => last().serverDrops());

      expect(result.current.wifiStatus).toBe("error");
      expect(result.current.wifiError).toMatch(/update pesacast on the phone/i);
      act(() => vi.advanceTimersByTime(120_000));
      expect(FakeWebSocket.instances).toHaveLength(1);
    });

    it("times out when the phone never answers", async () => {
      const { result } = renderHook(() => useWifi());
      await connect(result);
      await act(async () => {
        last().serverOpen();
      });
      act(() => vi.advanceTimersByTime(6_001));
      expect(result.current.wifiStatus).toBe("error");
      expect(result.current.wifiError).toMatch(/timed out/i);
    });

    it("does not auto-retry a connection that never worked", async () => {
      const { result } = renderHook(() => useWifi());
      await connect(result);
      act(() => {
        last().onerror?.();
        last().serverDrops();
      });
      act(() => vi.advanceTimersByTime(120_000));
      expect(FakeWebSocket.instances).toHaveLength(1);
      expect(result.current.wifiStatus).toBe("error");
    });
  });

  describe("reconnecting after an established connection drops", () => {
    it("retries after 2s with a fresh handshake", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      expect(result.current.wifiStatus).toBe("connecting");
      expect(FakeWebSocket.instances).toHaveLength(1);

      act(() => vi.advanceTimersByTime(1_999));
      expect(FakeWebSocket.instances).toHaveLength(1);
      act(() => vi.advanceTimersByTime(2));
      expect(FakeWebSocket.instances).toHaveLength(2);
      expect(last().url).toBe("ws://192.168.0.14:7877");

      await openAndAuthenticate();
      expect(JSON.parse(last().sent[0] as string).v).toBe(2);
      expect(result.current.wifiStatus).toBe("connected");
    });

    it("backs off exponentially up to 30s", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      const delays: number[] = [];
      for (let i = 0; i < 6; i++) {
        const before = FakeWebSocket.instances.length;
        act(() => last().serverDrops());
        let waited = 0;
        while (FakeWebSocket.instances.length === before && waited < 60_000) {
          act(() => vi.advanceTimersByTime(500));
          waited += 500;
        }
        delays.push(waited);
      }
      expect(delays).toEqual([2000, 4000, 8000, 16000, 30000, 30000]);
    });

    it("resets the backoff after a successful reconnect", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      act(() => vi.advanceTimersByTime(2_001)); // attempt 1
      act(() => last().serverDrops());
      act(() => vi.advanceTimersByTime(4_001)); // attempt 2 (4s)
      await openAndAuthenticate();

      const count = FakeWebSocket.instances.length;
      act(() => last().serverDrops());
      act(() => vi.advanceTimersByTime(2_001)); // back to 2s
      expect(FakeWebSocket.instances).toHaveLength(count + 1);
    });

    it("stops retrying if the phone rejects the token on a reconnect", async () => {
      // e.g. the token was regenerated on the phone while the desktop was connected.
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      act(() => vi.advanceTimersByTime(2_001));
      await act(async () => {
        last().serverOpen();
      });
      await vi.waitFor(() => expect(last().sent).toHaveLength(1));
      await last().completeHello();
      await vi.waitFor(() => expect(last().sent).toHaveLength(2));
      act(() => last().serverDrops());

      expect(result.current.wifiStatus).toBe("error");
      const count = FakeWebSocket.instances.length;
      act(() => vi.advanceTimersByTime(120_000));
      expect(FakeWebSocket.instances).toHaveLength(count);
    });

    it("stops retrying when the user disconnects", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      act(() => result.current.disconnectWifi());
      act(() => vi.advanceTimersByTime(120_000));

      expect(FakeWebSocket.instances).toHaveLength(1);
      expect(result.current.wifiStatus).toBe("disconnected");
    });

    it("does not reconnect after a manual disconnect of a live connection", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => result.current.disconnectWifi());
      act(() => vi.advanceTimersByTime(120_000));

      expect(FakeWebSocket.instances).toHaveLength(1);
      expect(result.current.wifiStatus).toBe("disconnected");
    });

    it("a new connect replaces a pending retry instead of doubling up", async () => {
      const { result } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      await connect(result, "192.168.0.20", 7877, "tok2");
      const count = FakeWebSocket.instances.length;
      act(() => vi.advanceTimersByTime(60_000));

      expect(FakeWebSocket.instances).toHaveLength(count);
      expect(last().url).toBe("ws://192.168.0.20:7877");
    });

    it("does not reconnect after the component unmounts", async () => {
      const { result, unmount } = renderHook(() => useWifi());
      await connected(result);

      act(() => last().serverDrops());
      unmount();
      act(() => vi.advanceTimersByTime(120_000));

      expect(FakeWebSocket.instances).toHaveLength(1);
    });
  });

  describe("device discovery", () => {
    it("stores what the backend finds", async () => {
      invoke.mockResolvedValueOnce([{ name: "PesaCast", ip: "192.168.0.14", port: 7877 }]);
      const { result } = renderHook(() => useWifi());
      await act(async () => {
        await result.current.scanForDevices();
      });
      expect(invoke).toHaveBeenCalledWith("discover_wifi_devices");
      expect(result.current.devices).toEqual([{ name: "PesaCast", ip: "192.168.0.14", port: 7877 }]);
      expect(result.current.scanError).toBeNull();
      expect(result.current.isScanning).toBe(false);
    });

    it("explains an empty result", async () => {
      invoke.mockResolvedValueOnce([]);
      const { result } = renderHook(() => useWifi());
      await act(async () => {
        await result.current.scanForDevices();
      });
      expect(result.current.scanError).toMatch(/no phones found/i);
    });

    it("surfaces backend errors", async () => {
      invoke.mockRejectedValueOnce("mDNS unavailable");
      const { result } = renderHook(() => useWifi());
      await act(async () => {
        await result.current.scanForDevices();
      });
      expect(result.current.scanError).toBe("mDNS unavailable");
      expect(result.current.isScanning).toBe(false);
    });
  });
});
