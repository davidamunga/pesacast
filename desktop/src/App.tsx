import { useEffect } from "react";
import { Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTab, TabsPanel } from "@/components/ui/tabs";
import { useMpesaTransactions } from "@/domains/transactions/useMpesaTransactions";
import { TransactionFeed } from "@/domains/transactions/feed";
import { useBle } from "@/domains/connection/useBle";
import { BluetoothSection } from "@/domains/connection/bluetooth-section";
import { StatusBadge } from "@/components/ui/status-badge";
import { useTheme } from "@/hooks/use-theme";
import { ensureNotificationPermission } from "@/hooks/use-notification-permission";
import { UpdateBanner } from "@/domains/updater/update-banner";

function App() {
  const { isDark, setIsDark } = useTheme();
  const { transactions, addTransaction } = useMpesaTransactions();
  const ble = useBle({ onTransaction: addTransaction });

  useEffect(() => {
    ensureNotificationPermission();
  }, []);

  return (
    <div className="flex h-screen flex-col bg-background text-foreground">
      <header className="flex shrink-0 items-center justify-between border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <img src="/logo.png" alt="PesaCast" className="size-8" />
          </div>
          <div>
            <h1 className="font-heading text-sm font-semibold leading-none">
              PesaCast
            </h1>
            <p className="mt-0.5 text-[11px] text-muted-foreground">
              M-PESA Notifications on PC
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <StatusBadge
            status={ble.bleStatus}
            label={ble.bleStatus === "connected" ? "BLE" : "BLE off"}
          />
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => setIsDark((d) => !d)}
            aria-label="Toggle theme"
          >
            {isDark ? <Sun /> : <Moon />}
          </Button>
        </div>
      </header>

      <Tabs
        defaultValue="feed"
        className="flex min-h-0 flex-1 flex-col gap-0 px-4 pt-3"
      >
        <TabsList className="w-full">
          <TabsTab value="feed" className="flex-1">
            Transactions
            {transactions.length > 0 && (
              <Badge className="ml-1.5" size="sm">
                {transactions.length}
              </Badge>
            )}
          </TabsTab>
          <TabsTab value="connect" className="flex-1">
            Connect
          </TabsTab>
        </TabsList>

        <TabsPanel value="feed" className="mt-3 min-h-0 flex-1">
          <TransactionFeed transactions={transactions} />
        </TabsPanel>

        <TabsPanel value="connect" className="mt-3 min-h-0 flex-1">
          <BluetoothSection {...ble} />
        </TabsPanel>
      </Tabs>

      <UpdateBanner />

      <footer className="shrink-0 border-t border-border px-4 py-2 text-center text-[11px] text-muted-foreground">
        Made by{" "}
        <a
          href="https://x.com/davidamunga_"
          target="_blank"
          rel="noopener noreferrer"
          className="underline underline-offset-2 hover:text-foreground"
        >
          David Amunga
        </a>
        {" · "}
        <a
          href="https://github.com/davidamunga/pesacast"
          target="_blank"
          rel="noopener noreferrer"
          className="underline underline-offset-2 hover:text-foreground"
        >
          GitHub
        </a>
      </footer>
    </div>
  );
}

export default App;
