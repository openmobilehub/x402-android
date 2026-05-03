// Diagnostic: sign a fixed TransferWithAuthorization with viem and print the
// EIP-712 hash + signature bytes. We compare these against the Kotlin output
// to isolate whether the bug is in our signing or our transport envelope.
//
// Run: node sigtest.js

import 'dotenv/config';
import { privateKeyToAccount } from 'viem/accounts';
import { hashTypedData } from 'viem';

const FIXED = {
  domain: {
    name: 'USDC',
    version: '2',
    chainId: 84532,
    verifyingContract: '0x036CbD53842c5426634e7929541eC2318f3dCF7e',
  },
  types: {
    TransferWithAuthorization: [
      { name: 'from', type: 'address' },
      { name: 'to', type: 'address' },
      { name: 'value', type: 'uint256' },
      { name: 'validAfter', type: 'uint256' },
      { name: 'validBefore', type: 'uint256' },
      { name: 'nonce', type: 'bytes32' },
    ],
  },
  primaryType: 'TransferWithAuthorization',
  message: {
    from: '0xEf9966c76afCa07798A9A65B619d897D77a6a0F9',
    to: '0x209693Bc6afc0C5328bA36FaF03C514EF312287C',
    value: 10000n,
    validAfter: 1000n,
    validBefore: 9999999999n,
    nonce: '0x1111111111111111111111111111111111111111111111111111111111111111',
  },
};

const pk = (process.env.PRIVATE_KEY?.startsWith('0x') ? process.env.PRIVATE_KEY : `0x${process.env.PRIVATE_KEY}`);
const account = privateKeyToAccount(pk);

const hash = hashTypedData(FIXED);
const signature = await account.signTypedData(FIXED);

console.log('address:    ', account.address);
console.log('eip712_hash:', hash);
console.log('signature:  ', signature);
