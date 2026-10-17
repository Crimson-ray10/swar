export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  return res.status(200).json([
    {
      id: 'responder_mesh_01',
      name: 'Field Responder Mesh (Alpha)',
      addresses: ['192.168.49.2'],
      port: 8988,
      transports: ['wifi_direct', 'wifi_lan', 'bluetooth'],
      lastSeen: Date.now(),
    },
    {
      id: 'base_station_gateway',
      name: 'Base Station Relay (Emergency Command)',
      addresses: ['10.42.0.1'],
      port: 8990,
      transports: ['wifi_lan'],
      lastSeen: Date.now() - 4500,
    }
  ]);
}
