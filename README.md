# Distributed Banking System with Token Ring

This project implements a distributed banking system that guarantees
mutual exclusion using the Token Ring algorithm.

## Overview
- 4 distributed ATM nodes
- Executed on localhost
- No shared memory
- Coordination via message passing (TCP sockets)
- Mutual exclusion guaranteed by a circulating token

## Features
- Deposit and withdraw transactions
- Token Ring mutual exclusion
- Token loss detection with timeout
- Token regeneration
- Simulated node crashes
- Distributed termination using STOP token

## Topology
ATM1 → ATM2 → ATM3 → ATM4 → ATM1

## Token Format
TOKEN:\<id\>:\<balance\>[:STOP]


## How to Run

Compile:
```bash
javac ATMNode.java
```
Run:
```bash
java ATMNode 1 5001 5002
java ATMNode 2 5002 5003
java ATMNode 3 5003 5004
java ATMNode 4 5004 5001
```
Optional crash simulation:
```bash
java ATMNode 3 5003 5004 CRASH
```

## Initial State

Initial balance: 1000

Initial token owner: ATM1

## Termination

The system terminates automatically when the token completes a full
ring and returns to ATM1.

## Course Context

INTELLIGENZA ARTIFICIALE DISTRIBUITA - 0322509INGINF05I
