import { useState, useEffect } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { Database, FolderOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Popover, PopoverTrigger, PopoverPopup } from "@/components/ui/popover";
import {
  PERSIST_STORAGE_KEY_ENABLED,
  PERSIST_STORAGE_KEY_PATH,
} from "./usePersistence";

interface PersistenceSettingsProps {
  onSettingsChanged?: () => void;
}

export function PersistenceSettings({
  onSettingsChanged,
}: PersistenceSettingsProps) {
  const [enabled, setEnabled] = useState(
    () => localStorage.getItem(PERSIST_STORAGE_KEY_ENABLED) === "true",
  );
  const [dbPath, setDbPath] = useState(
    () => localStorage.getItem(PERSIST_STORAGE_KEY_PATH) ?? "",
  );

  useEffect(() => {
    localStorage.setItem(PERSIST_STORAGE_KEY_ENABLED, String(enabled));
    onSettingsChanged?.();
  }, [enabled, onSettingsChanged]);

  useEffect(() => {
    localStorage.setItem(PERSIST_STORAGE_KEY_PATH, dbPath);
    onSettingsChanged?.();
  }, [dbPath, onSettingsChanged]);

  async function handleBrowse() {
    const selected = await open({
      directory: true,
      multiple: false,
      title: "Choose folder to save transactions database",
    });
    if (selected && typeof selected === "string") {
      setDbPath(`${selected}/pesacast.db`);
    }
  }

  return (
    <Popover>
      <PopoverTrigger
        render={
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Persistence settings"
          >
            <Database className="size-4" />
          </Button>
        }
      />
      <PopoverPopup side="bottom" align="end" className="w-80">
        <div className="flex flex-col gap-4">
          <div className="flex items-center justify-between gap-3 rounded-md   py-2.5">
            <label
              htmlFor="persist-enabled"
              className="cursor-pointer text-sm font-medium leading-none"
            >
              Enable local persistence
            </label>
            <input
              id="persist-enabled"
              type="checkbox"
              checked={enabled}
              onChange={(e) =>
                setEnabled((e.target as HTMLInputElement).checked)
              }
              className="accent-primary size-4"
            />
          </div>

          {enabled && (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="db-path" className="text-xs">
                Database file path
              </Label>
              <div className="flex gap-1.5">
                <input
                  id="db-path"
                  type="text"
                  readOnly
                  value={dbPath}
                  placeholder="Choose a folder…"
                  className="flex h-8 flex-1 rounded-md border border-input bg-background px-3 py-1 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1"
                />
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleBrowse}
                  className="gap-1.5 shrink-0"
                >
                  <FolderOpen className="size-3.5" />
                  Browse
                </Button>
              </div>
              {!dbPath && (
                <p className="text-[11px] text-muted-foreground">
                  No folder selected — transactions will not be saved until you
                  choose one.
                </p>
              )}
              {dbPath && (
                <p className="truncate text-[11px] text-muted-foreground">
                  {dbPath}
                </p>
              )}
            </div>
          )}

          {!enabled && (
            <p className="text-xs text-muted-foreground">
              When disabled, transactions are only kept in memory and lost when
              the app closes.
            </p>
          )}
        </div>
      </PopoverPopup>
    </Popover>
  );
}
