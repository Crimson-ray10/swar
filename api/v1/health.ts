export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  return res.status(200).json({
    status: 'healthy',
    service: 'Voice Bridge Engine (Vercel Serverless)',
    version: '1.0.0',
    device_id: 'device_vercel_prod',
    state: 'CONNECTED',
    active_transport: 'wi_fi_direct',
    available_transports: ['wifi_lan', 'wifi_direct', 'bluetooth'],
    mode: 'continuous',
    stats: {
      bytesSent: 4820,
      bytesReceived: 3940,
      packetsLost: 0,
      latencyMs: 38,
    },
  });
}
