// main.dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Battery Stats PoC',
      theme: ThemeData(primarySwatch: Colors.blue, useMaterial3: true),
      home: const BatteryStatsPage(),
    );
  }
}

class BatteryStatsPage extends StatefulWidget {
  const BatteryStatsPage({super.key});

  @override
  State<BatteryStatsPage> createState() => _BatteryStatsPageState();
}

class _BatteryStatsPageState extends State<BatteryStatsPage> {
  static const platform = MethodChannel('com.example.battery_stats/battery');
  List<Map<String, dynamic>> _batteryStats = [];
  bool _isLoading = false;
  String _errorMessage = '';

  @override
  void initState() {
    super.initState();
    _fetchBatteryStats();
  }

  Future<void> _fetchBatteryStats() async {
    setState(() {
      _isLoading = true;
      _errorMessage = '';
    });

    try {
      final result = await platform.invokeMethod('getBatteryUsageStats');

      // Convert the raw result into the proper type
      final List<Map<String, dynamic>> batteryStats =
          (result as List).map((item) {
            // Cast each map item with proper types
            return Map<String, dynamic>.from(item as Map);
          }).toList();

      setState(() {
        _batteryStats = batteryStats;
        _isLoading = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _isLoading = false;
        _errorMessage = 'Error: ${e.message}';
      });
      debugPrint('Error: $e');
      debugPrintStack();
    } catch (e) {
      setState(() {
        _isLoading = false;
        _errorMessage = 'Unexpected error: $e';
      });
      debugPrint('Error: $e');
      debugPrintStack();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Battery Usage Stats')),
      body:
          _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _errorMessage.isNotEmpty
              ? Center(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        _errorMessage,
                        style: const TextStyle(color: Colors.red),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: _fetchBatteryStats,
                        child: const Text('Try Again'),
                      ),
                    ],
                  ),
                ),
              )
              : _batteryStats.isEmpty
              ? const Center(child: Text('No battery stats available'))
              : RefreshIndicator(
                onRefresh: _fetchBatteryStats,
                child: ListView.builder(
                  itemCount: _batteryStats.length,
                  itemBuilder: (context, index) {
                    final stat = _batteryStats[index];
                    return BatteryStatCard(stat: stat);
                  },
                ),
              ),
    );
  }
}

class BatteryStatCard extends StatelessWidget {
  final Map<String, dynamic> stat;

  const BatteryStatCard({super.key, required this.stat});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: Colors.blue.shade100,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(Icons.battery_full, color: Colors.blue.shade800),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    stat['packageName'] ?? 'Unknown App',
                    style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 16,
                    ),
                  ),
                ),
              ],
            ),
            const Divider(),
            _buildStatRow(
              'Power Usage',
              '${stat['powerUsagePercent'] ?? 'N/A'}%',
            ),
            _buildStatRow(
              'Foreground Usage',
              _formatDuration(stat['foregroundUsageTimeMs']),
            ),
            _buildStatRow(
              'Background Usage',
              _formatDuration(stat['backgroundUsageTimeMs']),
            ),
            if (stat['consumedPowerMah'] != null)
              _buildStatRow(
                'Battery Consumed',
                '${stat['consumedPowerMah']} mAh',
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }

  String _formatDuration(dynamic milliseconds) {
    if (milliseconds == null) return 'N/A';

    final ms =
        milliseconds is int
            ? milliseconds
            : int.tryParse(milliseconds.toString()) ?? 0;
    final minutes = ms ~/ 60000;
    final hours = minutes ~/ 60;
    final mins = minutes % 60;

    if (hours > 0) {
      return '$hours h $mins min';
    } else {
      return '$mins min';
    }
  }
}
