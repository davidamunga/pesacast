import { useState } from "react";
import * as XLSX from "xlsx";
import { Download, FileSpreadsheet, CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverTrigger,
  PopoverPopup,
  PopoverTitle,
  PopoverDescription,
  PopoverClose,
} from "@/components/ui/popover";
import { MpesaTransaction } from "./types";
import { formatTime, directionLabel } from "./utils";

interface ExportTransactionsProps {
  transactions: MpesaTransaction[];
  filteredTransactions: MpesaTransaction[];
}

type ExportScope = "filtered" | "all";

function buildWorksheet(transactions: MpesaTransaction[]) {
  const rows = transactions.map((txn) => ({
    Time: formatTime(txn.time),
    Type: txn.type,
    Direction: directionLabel(txn.direction),
    From: txn.from,
    Reference: txn.ref,
    Amount: txn.amount,
    Currency: txn.currency,
    Balance: txn.balance,
  }));

  return XLSX.utils.json_to_sheet(rows);
}

function downloadXlsx(transactions: MpesaTransaction[], filename: string) {
  const wb = XLSX.utils.book_new();
  const ws = buildWorksheet(transactions);
  XLSX.utils.book_append_sheet(wb, ws, "Transactions");

  const wbout = XLSX.write(wb, { bookType: "xlsx", type: "array" });
  const blob = new Blob([wbout], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function ExportTransactions({
  transactions,
  filteredTransactions,
}: ExportTransactionsProps) {
  const [scope, setScope] = useState<ExportScope>("filtered");
  const [exported, setExported] = useState(false);

  const isFiltered = filteredTransactions.length !== transactions.length;
  const targetTransactions =
    scope === "all" ? transactions : filteredTransactions;

  function handleExport() {
    const timestamp = new Date().toISOString().slice(0, 10);
    const filename = `mpesa-transactions-${timestamp}.xlsx`;
    downloadXlsx(targetTransactions, filename);
    setExported(true);
    setTimeout(() => setExported(false), 2000);
  }

  return (
    <Popover>
      <PopoverTrigger
        render={
          <Button variant="outline" size="sm" className="gap-1.5">
            <Download className="size-3.5" />
            Export
          </Button>
        }
      />
      <PopoverPopup side="bottom" align="end" className="w-64">
        <div className="flex flex-col gap-3">
          <div>
            <PopoverTitle>Export to Excel</PopoverTitle>
            <PopoverDescription className="mt-0.5">
              Download transactions as an .xlsx file
            </PopoverDescription>
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-foreground">
              Export scope
            </span>
            <div className="flex flex-col gap-1">
              <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent/50">
                <input
                  type="radio"
                  name="export-scope"
                  value="filtered"
                  checked={scope === "filtered"}
                  onChange={() => setScope("filtered")}
                  className="accent-primary"
                />
                <span>
                  {isFiltered ? "Filtered" : "All"} transactions
                  <span className="ml-1.5 text-xs text-muted-foreground">
                    ({filteredTransactions.length})
                  </span>
                </span>
              </label>
              {isFiltered && (
                <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent/50">
                  <input
                    type="radio"
                    name="export-scope"
                    value="all"
                    checked={scope === "all"}
                    onChange={() => setScope("all")}
                    className="accent-primary"
                  />
                  <span>
                    All transactions
                    <span className="ml-1.5 text-xs text-muted-foreground">
                      ({transactions.length})
                    </span>
                  </span>
                </label>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <PopoverClose
              render={
                <Button
                  variant="default"
                  size="sm"
                  className="flex-1"
                  onClick={handleExport}
                  disabled={targetTransactions.length === 0}
                >
                  {exported ? (
                    <>
                      <CheckCircle2 className="size-3.5" />
                      Downloaded
                    </>
                  ) : (
                    <>
                      <FileSpreadsheet className="size-3.5" />
                      Download .xlsx
                    </>
                  )}
                </Button>
              }
            />
          </div>
        </div>
      </PopoverPopup>
    </Popover>
  );
}
