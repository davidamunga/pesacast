import { useState } from "react";
import { Inbox, Search } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import {
  Empty,
  EmptyHeader,
  EmptyTitle,
  EmptyDescription,
} from "@/components/ui/empty";
import { MpesaTransaction } from "./types";
import { formatAmount, formatTime, directionLabel } from "./utils";

interface TransactionFeedProps {
  transactions: MpesaTransaction[];
}

export function TransactionFeed({ transactions }: TransactionFeedProps) {
  const [query, setQuery] = useState("");

  const filtered = query.trim()
    ? transactions.filter((txn) => {
        const q = query.toLowerCase();
        return (
          txn.from.toLowerCase().includes(q) ||
          txn.ref.toLowerCase().includes(q) ||
          directionLabel(txn.direction).toLowerCase().includes(q) ||
          formatAmount(txn.currency, txn.amount).toLowerCase().includes(q)
        );
      })
    : transactions;

  if (transactions.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <div className="mb-4 flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <Inbox className="size-6" />
          </div>
          <EmptyTitle>No transactions yet</EmptyTitle>
          <EmptyDescription>
            Connect your Android device and send a test transaction
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="px-1 pb-2 pt-1">
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="h-8 pl-8 text-xs"
            placeholder="Search by name, ref, or amount…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      </div>
      <ScrollArea className="flex-1">
        {filtered.length === 0 ? (
          <div className="flex flex-col items-center gap-1 px-4 py-8 text-center">
            <span className="text-sm font-medium text-muted-foreground">
              No results for "{query}"
            </span>
            <span className="text-xs text-muted-foreground">
              Try a different name, ref, or amount
            </span>
          </div>
        ) : (
          <ul className="flex flex-col">
            {filtered.map((txn, i) => (
              <li key={`${txn.ref}-${i}`}>
                {i > 0 && <Separator />}
                <div className="flex items-start justify-between gap-3 px-1 py-3">
                  <div className="flex min-w-0 flex-col gap-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm font-medium">{txn.from}</span>
                      <Badge
                        variant={txn.direction === "received" ? "success" : "error"}
                        size="sm"
                      >
                        {directionLabel(txn.direction)}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                      <span>Ref: {txn.ref}</span>
                      <span>·</span>
                      <span>{formatTime(txn.time)}</span>
                    </div>
                    <span className="text-xs text-muted-foreground">
                      Bal: {formatAmount(txn.currency, txn.balance)}
                    </span>
                  </div>
                  <span
                    className={`whitespace-nowrap text-sm font-semibold tabular-nums ${
                      txn.direction === "received"
                        ? "text-success-foreground"
                        : "text-destructive-foreground"
                    }`}
                  >
                    {txn.direction === "received" ? "+" : "−"}
                    {formatAmount(txn.currency, txn.amount)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </ScrollArea>
    </div>
  );
}
