## 1. Happy path: the sample transaction from `requirements.md`

Tested by: `HappyPathIT`; `fee_calculation.feature`, "The sample transaction from the requirements …"

```bash
curl -i -X POST localhost:8082/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_001",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}'
```



## 2. The same `transaction_id` again: no second charge

The workflow is keyed by `transaction_id`: sending the id again gets the first charge's response, from the local cache or
Postgres once it is charged, otherwise by attaching to its first run.

Tested by: `DuplicatesIT`; `idempotency.feature`



## 3. Other transaction types: each has its own fee rule

Tested by: `HappyPathIT`; `fee_calculation.feature`, 

### 3a. Bank Transfer

```bash
curl -i -X POST localhost:8082/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_002",
  "amount": 100,
  "asset": "EUR",
  "asset_type": "FIAT",
  "type": "Bank Transfer",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}'
```


### 3b. Merchant Payment

```bash
curl -i ' -X POST localhost:8082/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_003",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Merchant Payment",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}'
```



## 4. Unknown transaction type: rejected, nothing charged

Tested by: `RejectedIT`, "type not supported"; `validation.feature`, "Only the types that have a fee rule";
`AppContainerIT`, with the app in a container

```bash
curl -i -X POST localhost:8082/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_005",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Crypto Purchase",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}'
```