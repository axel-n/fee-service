# Technology Challenge: Backend Kotlin & Restate Implementation

## 1. Purpose of the Challenge

The purpose of this coding challenge is:

- To see what kind of coder you are.
- So you can see what kind of technology you'll be working on.
- So we can work together to solve a tricky problem and see if our communication skills are compatible.
- To see how you use AI.

> **Submission & Next Steps:** When you've completed your solution, send us your Git repository and we'll schedule a
> call for you to present your solution to us and give both parties the opportunity to ask questions.

## 2. Challenge Overview

Your challenge is to build a RESTful API for a fees workflow that uses Kotlin services to calculate, charge, and record
the fees for a transaction submitted to the public API endpoint. The workflow will be implemented using Restate.

Your workflow should persist the result of the fee calculations and charges. If you have time, introduce other
transaction types and implement fees for them.

## 3. Test Case & API Specification

Your test case should create a transaction and submit it to the fees endpoint:

```
POST /transaction/fee
```

### Sample Request Payload

```json
{
  "transaction_id": "txn_001",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
```

### Expected Response Payload

```json
{
  "transaction_id": "txn_001",
  "amount": 1000,
  "asset": "USD",
  "type": "Mobile Top Up",
  "fee": 1.5,
  "rate": 0.0015,
  "description": "Standard fee rate of 0.15%"
}
```

## 4. Solution Requirements

- **Kotlin:** Use Kotlin for the implementation of services.
- **Restate:** Use Restate for workflow creation and execution.
- **Documentation:** Include a comprehensive Readme explaining architecture, setup, and execution.
- **Testing:** Have unit tests included (bonus points for using BDDs that describe the test cases in the form of scenarios and features).
- **Extensibility (Optional):** Introduce additional transaction types and implement appropriate fees for them if time permits.
