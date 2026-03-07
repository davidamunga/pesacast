export interface MpesaTransaction {
  type: string;
  direction: string;
  amount: number;
  currency: string;
  from: string;
  ref: string;
  time: string;
  balance: number;
  transaction_cost?: number;
}
