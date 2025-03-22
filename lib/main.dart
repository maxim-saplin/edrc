// main.dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:intl/intl.dart';

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

class _BatteryStatsPageState extends State<BatteryStatsPage>
    with SingleTickerProviderStateMixin {
  static const platform = MethodChannel('com.example.battery_stats/battery');
  List<Map<String, dynamic>> _batteryStats = [];
  List<Map<String, dynamic>> _batteryHistory = [];
  bool _isLoading = false;
  String _errorMessage = '';
  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _fetchBatteryStats();
    _fetchBatteryHistory();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
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

  Future<void> _fetchBatteryHistory() async {
    try {
      final result = await platform.invokeMethod('getBatteryLevelHistory');

      // Convert the raw result into the proper type
      final List<Map<String, dynamic>> batteryHistory =
          (result as List).map((item) {
            return Map<String, dynamic>.from(item as Map);
          }).toList();

      setState(() {
        _batteryHistory = batteryHistory;
      });
    } on PlatformException catch (e) {
      debugPrint('Error fetching battery history: ${e.message}');
    } catch (e) {
      debugPrint('Unexpected error fetching battery history: $e');
    }
  }

  Future<void> _refreshData() async {
    await Future.wait([_fetchBatteryStats(), _fetchBatteryHistory()]);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Battery Usage Stats'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'App Usage', icon: Icon(Icons.apps)),
            Tab(text: 'Battery History', icon: Icon(Icons.battery_full)),
          ],
        ),
      ),
      body: SafeArea(
        child: Padding(
          padding: EdgeInsets.only(
            bottom: MediaQuery.of(context).padding.bottom,
          ),
          child: TabBarView(
            controller: _tabController,
            children: [
              // First tab - App usage stats
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
                    onRefresh: _refreshData,
                    child: ListView.builder(
                      itemCount: _batteryStats.length,
                      itemBuilder: (context, index) {
                        final stat = _batteryStats[index];
                        return BatteryStatCard(stat: stat);
                      },
                    ),
                  ),

              // Second tab - Battery level history graph
              RefreshIndicator(
                onRefresh: _refreshData,
                child:
                    _batteryHistory.isEmpty
                        ? const Center(
                          child: Text('No battery history available'),
                        )
                        : Padding(
                          padding: const EdgeInsets.all(16.0),
                          child: BatteryHistoryChart(
                            batteryHistory: _batteryHistory,
                          ),
                        ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class BatteryHistoryChart extends StatelessWidget {
  final List<Map<String, dynamic>> batteryHistory;

  const BatteryHistoryChart({super.key, required this.batteryHistory});

  @override
  Widget build(BuildContext context) {
    // Sort the battery history with most recent timestamps at the end
    final sortedHistory = List<Map<String, dynamic>>.from(
      batteryHistory,
    )..sort((a, b) => (a['timestamp'] as int).compareTo(b['timestamp'] as int));

    return Column(
      children: [
        const SizedBox(height: 20),
        Text(
          'Battery Level Past 24 Hours',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 30),
        Expanded(
          child: LineChart(
            LineChartData(
              gridData: const FlGridData(show: true),
              titlesData: FlTitlesData(
                bottomTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    getTitlesWidget: (value, meta) {
                      if (value.toInt() % (sortedHistory.length ~/ 4) != 0) {
                        return const SizedBox.shrink();
                      }

                      final index = value.toInt();
                      if (index >= 0 && index < sortedHistory.length) {
                        final timestamp =
                            sortedHistory[index]['timestamp'] as int;
                        final date = DateTime.fromMillisecondsSinceEpoch(
                          timestamp,
                        );
                        return Padding(
                          padding: const EdgeInsets.only(top: 8.0),
                          child: Text(
                            DateFormat('HH:mm').format(date),
                            style: const TextStyle(fontSize: 10),
                          ),
                        );
                      }
                      return const SizedBox.shrink();
                    },
                    reservedSize: 30,
                  ),
                ),
                leftTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    getTitlesWidget: (value, meta) {
                      if (value % 20 != 0) return const SizedBox.shrink();
                      return Padding(
                        padding: const EdgeInsets.only(right: 8.0),
                        child: Text('${value.toInt()}%'),
                      );
                    },
                    reservedSize: 35,
                  ),
                ),
                topTitles: const AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
                rightTitles: const AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
              ),
              borderData: FlBorderData(show: true),
              minX: 0,
              maxX: (sortedHistory.length - 1).toDouble(),
              minY: 0,
              maxY: 100,
              lineBarsData: [
                LineChartBarData(
                  spots: List.generate(
                    sortedHistory.length,
                    (index) => FlSpot(
                      index.toDouble(),
                      (sortedHistory[index]['batteryLevel'] as int).toDouble(),
                    ),
                  ),
                  isCurved: true,
                  color: Colors.blue,
                  barWidth: 3,
                  isStrokeCapRound: true,
                  dotData: const FlDotData(show: false),
                  belowBarData: BarAreaData(
                    show: true,
                    color: Colors.blue.withOpacity(0.2),
                  ),
                ),
              ],
              lineTouchData: LineTouchData(
                touchTooltipData: LineTouchTooltipData(
                  tooltipBgColor: Colors.blueAccent.withOpacity(0.8),
                  getTooltipItems: (touchedSpots) {
                    return touchedSpots.map((touchedSpot) {
                      final index = touchedSpot.x.toInt();
                      if (index >= 0 && index < sortedHistory.length) {
                        final timestamp =
                            sortedHistory[index]['timestamp'] as int;
                        final date = DateTime.fromMillisecondsSinceEpoch(
                          timestamp,
                        );
                        final level =
                            sortedHistory[index]['batteryLevel'] as int;
                        return LineTooltipItem(
                          '${DateFormat('HH:mm').format(date)}\n$level%',
                          const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                          ),
                        );
                      }
                      return null;
                    }).toList();
                  },
                ),
              ),
            ),
          ),
        ),
      ],
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
