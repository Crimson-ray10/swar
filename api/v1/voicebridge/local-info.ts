export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const hostHeader = (req.headers['x-forwarded-host'] || req.headers.host || 'localhost') as string;
  const proto = (req.headers['x-forwarded-proto'] || 'https') as string;
  const currentOrigin = `${proto}://${hostHeader}`;

  return res.status(200).json({
    deviceId: 'vb_cloud_node',
    deviceName: 'VoiceBridge-Cloud-Intercom',
    localIps: ['127.0.0.1', '192.168.49.1', '192.168.43.1'],
    primaryIp: '192.168.49.1',
    tunnelUrl: currentOrigin,
    ports: {
      http: 5173,
      wifi_lan: 8988,
      wifi_direct: 8990,
      bluetooth: 8992,
      discovery_udp: 8989,
    },
    transports: {
      wifi_direct: { port: 8990, active: true, protocol: 'Wi-Fi Direct P2P (Port 8990)' },
      bluetooth: { port: 8992, active: true, protocol: 'Bluetooth RFCOMM Stream (Port 8992)' },
      wifi_lan: { port: 8988, active: true, protocol: 'Wi-Fi LAN Mesh (Port 8988)' },
    },
    status: {
      state: 'CONNECTED',
      activeTransport: 'wifi_direct',
      availableTransports: {
        wifi_direct: true,
        wifi_lan: true,
        bluetooth: true,
      },
      stats: {
        messagesSent: 1,
        messagesReceived: 1,
        drops: 0,
        pingMs: 14,
      },
    },
  });
}
