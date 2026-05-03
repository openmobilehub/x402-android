import 'dotenv/config';
import axios from 'axios';
import {
  wrapAxiosWithPaymentFromConfig,
  decodePaymentResponseHeader,
} from '@x402/axios';
import { ExactEvmScheme } from '@x402/evm';
import { privateKeyToAccount } from 'viem/accounts';

// Base Sepolia per CAIP-2. Hard-coded on purpose: this experiment is testnet-only.
const NETWORK = 'eip155:84532';
const BASESCAN = 'https://sepolia.basescan.org/tx/';

const url = process.argv[2];
if (!url) {
  console.error('usage: node pay.js <url>');
  process.exit(2);
}

const rawKey = process.env.PRIVATE_KEY;
if (!rawKey) {
  console.error('PRIVATE_KEY missing — copy ../.env.example to .env and fill it in');
  process.exit(2);
}

const pk = rawKey.startsWith('0x') ? rawKey : `0x${rawKey}`;
const account = privateKeyToAccount(pk);

console.log(`payer:   ${account.address}`);
console.log(`network: ${NETWORK} (Base Sepolia)`);
console.log(`target:  ${url}`);

const api = wrapAxiosWithPaymentFromConfig(axios.create({ timeout: 30_000 }), {
  schemes: [{ network: NETWORK, client: new ExactEvmScheme(account) }],
});

try {
  const res = await api.get(url);
  console.log(`\nstatus:  ${res.status}`);

  const pr = res.headers['payment-response'] ?? res.headers['x-payment-response'];
  if (pr) {
    const decoded = decodePaymentResponseHeader(pr);
    console.log('payment:', JSON.stringify(decoded, null, 2));
    const tx = decoded.transaction ?? decoded.txHash;
    if (tx) console.log(`\nbasescan: ${BASESCAN}${tx}`);
  } else {
    console.log('(no payment-response header — endpoint may have served you for free)');
  }

  const body = typeof res.data === 'string' ? res.data : JSON.stringify(res.data, null, 2);
  console.log('\nbody:\n' + body);
} catch (err) {
  if (err.response) {
    console.error(`\nhttp ${err.response.status}`);
    console.error(JSON.stringify(err.response.data, null, 2));
  } else {
    console.error('\n' + (err.stack ?? err.message));
  }
  process.exit(1);
}
