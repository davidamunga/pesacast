use rusqlite::{params, Connection, Result as SqlResult};
use tauri::command;

use crate::model::MpesaTransaction;

fn open(path: &str) -> SqlResult<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch("PRAGMA journal_mode=WAL;")?;
    Ok(conn)
}

pub fn init_db(path: &str) -> SqlResult<()> {
    let conn = open(path)?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS transactions (
            ref         TEXT NOT NULL,
            time        TEXT NOT NULL,
            txn_type    TEXT NOT NULL,
            direction   TEXT NOT NULL,
            amount      REAL NOT NULL,
            currency    TEXT NOT NULL,
            party       TEXT NOT NULL,
            balance     REAL NOT NULL,
            txn_cost    REAL,
            PRIMARY KEY (ref, time)
        );",
    )?;
    Ok(())
}

pub fn save_transaction(path: &str, txn: &MpesaTransaction) -> SqlResult<()> {
    let conn = open(path)?;
    conn.execute(
        "INSERT OR IGNORE INTO transactions
            (ref, time, txn_type, direction, amount, currency, party, balance, txn_cost)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            txn.reference,
            txn.time,
            txn.txn_type,
            txn.direction,
            txn.amount,
            txn.currency,
            txn.from,
            txn.balance,
            txn.transaction_cost,
        ],
    )?;
    Ok(())
}

pub fn load_transactions(path: &str) -> SqlResult<Vec<MpesaTransaction>> {
    let conn = open(path)?;
    let mut stmt = conn.prepare(
        "SELECT ref, time, txn_type, direction, amount, currency, party, balance, txn_cost
         FROM transactions
         ORDER BY time DESC
         LIMIT 100",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(MpesaTransaction {
            reference: row.get(0)?,
            time: row.get(1)?,
            txn_type: row.get(2)?,
            direction: row.get(3)?,
            amount: row.get(4)?,
            currency: row.get(5)?,
            from: row.get(6)?,
            balance: row.get(7)?,
            transaction_cost: row.get(8)?,
        })
    })?;
    rows.collect()
}

// ── Tauri commands ──────────────────────────────────────────────────────────

#[command]
pub fn db_init(path: String) -> Result<(), String> {
    init_db(&path).map_err(|e| e.to_string())
}

#[command]
pub fn db_save_transaction(path: String, txn: MpesaTransaction) -> Result<(), String> {
    save_transaction(&path, &txn).map_err(|e| e.to_string())
}

#[command]
pub fn db_load_transactions(path: String) -> Result<Vec<MpesaTransaction>, String> {
    load_transactions(&path).map_err(|e| e.to_string())
}
