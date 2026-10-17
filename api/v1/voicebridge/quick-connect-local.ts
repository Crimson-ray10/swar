export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const transport = req.body?.transport || 'wifi_direct';
  return res.status(200).json({
    success: true,
    activeTransport: transport,
    state: 'CONNECTED',
  });
}
