import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../providers/linux_environment_provider.dart';

class _Mocha {
  static const bg      = Color(0xFF1E1E2E);
  static const surface = Color(0xFF181825);
  static const overlay = Color(0xFF313244);
  static const muted   = Color(0xFF45475A);
  static const subtext = Color(0xFFBAC2DE);
  static const green   = Color(0xFFA6E3A1);
  static const red     = Color(0xFFF38BA8);
  static const yellow  = Color(0xFFF9E2AF);
  static const mauve   = Color(0xFFCBA6F7);
}

class TerminalPanel extends StatefulWidget {
  final VoidCallback onClose;
  const TerminalPanel({super.key, required this.onClose});

  @override
  State<TerminalPanel> createState() => _TerminalPanelState();
}

class _TerminalPanelState extends State<TerminalPanel> {
  static const _sessionChannel = MethodChannel('com.pyom/termux_session');
  MethodChannel? _viewChannel;

  bool _loading = true;
  String _errorMsg = '';
  Map<String, dynamic>? _pendingArgs;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _fetchSessionArgs());
  }

  Future<void> _fetchSessionArgs() async {
    if (!mounted) return;
    setState(() { _loading = true; _errorMsg = ''; });
    try {
      final linuxProvider = context.read<LinuxEnvironmentProvider>();
      if (!linuxProvider.isReady) {
        setState(() { _loading = false; _errorMsg = 'Linux environment not ready.\nInstall an environment in Settings first.'; });
        return;
      }
      final args = await _sessionChannel.invokeMapMethod<String, dynamic>('getProotSessionArgs');
      if (!mounted) return;
      if (args == null) throw Exception('No session args from native');
      _pendingArgs = Map<String, dynamic>.from(args);
      if (_viewChannel != null) await _startSession();
      setState(() { _loading = false; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _loading = false; _errorMsg = e.toString(); });
    }
  }

  Future<void> _startSession() async {
    final args = _pendingArgs; final ch = _viewChannel;
    if (args == null || ch == null) return;
    final env = List<String>.from(args['env'] as List);
    // FIX: Pass full args list directly — no shell script file needed
    // Native side now provides args = ["/system/bin/sh", "-c", "<proot command>"]
    final argsList = args['args'] != null
        ? List<String>.from(args['args'] as List)
        : null;
    await ch.invokeMethod('startSession', {
      'shellPath': args['shellPath'] as String,
      'cwd': args['cwd'] as String,
      'env': env,
      if (argsList != null) 'args': argsList,
    });
  }

  void _onPlatformViewCreated(int viewId) {
    _viewChannel = MethodChannel('com.pyom/termux_terminal_$viewId');
    _viewChannel!.setMethodCallHandler((call) async {
      if (call.method == 'onSessionFinished' && mounted) setState(() {});
    });
    if (_pendingArgs != null) _startSession();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: _Mocha.bg,
      child: Column(children: [
        _buildHeader(),
        Expanded(child: _buildBody()),
      ]),
    );
  }

  Widget _buildHeader() {
    return Container(
      height: 34,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: const BoxDecoration(
        color: _Mocha.surface,
        border: Border(bottom: BorderSide(color: _Mocha.overlay)),
      ),
      child: Row(children: [
        _dot(_Mocha.red), const SizedBox(width: 6),
        _dot(_Mocha.yellow), const SizedBox(width: 6),
        _dot(_Mocha.green), const SizedBox(width: 12),
        const Icon(Icons.terminal, size: 14, color: _Mocha.mauve),
        const SizedBox(width: 6),
        const Text('TERMINAL', style: TextStyle(
          fontFamily: 'monospace', fontSize: 11, fontWeight: FontWeight.w700,
          color: _Mocha.subtext, letterSpacing: 1.2,
        )),
        if (_loading) ...[
          const SizedBox(width: 8),
          const SizedBox(width: 10, height: 10,
            child: CircularProgressIndicator(strokeWidth: 1.5, color: _Mocha.green)),
        ],
        const Spacer(),
        _btn(Icons.stop_circle_outlined, 'Ctrl+C', () =>
          _viewChannel?.invokeMethod('sendInput', {'input': '\x03'})),
        _btn(Icons.clear_all_rounded, 'Clear', () =>
          _viewChannel?.invokeMethod('sendInput', {'input': 'clear\n'})),
        _btn(Icons.refresh_rounded, 'Restart', _fetchSessionArgs),
        _btn(Icons.close_rounded, 'Close', widget.onClose),
      ]),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        CircularProgressIndicator(color: _Mocha.mauve),
        SizedBox(height: 16),
        Text('Starting terminal…',
          style: TextStyle(color: _Mocha.subtext, fontFamily: 'monospace', fontSize: 13)),
      ],
    ));

    if (_errorMsg.isNotEmpty) return Center(child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        const Icon(Icons.error_outline, color: _Mocha.red, size: 40),
        const SizedBox(height: 12),
        Text(_errorMsg,
          style: const TextStyle(color: _Mocha.red, fontFamily: 'monospace', fontSize: 12),
          textAlign: TextAlign.center),
        const SizedBox(height: 16),
        ElevatedButton.icon(
          icon: const Icon(Icons.refresh, size: 16),
          label: const Text('Retry'),
          style: ElevatedButton.styleFrom(
            backgroundColor: _Mocha.mauve, foregroundColor: _Mocha.surface),
          onPressed: _fetchSessionArgs,
        ),
      ]),
    ));

    // Native Termux PTY terminal — real interactive shell
    return AndroidView(
      viewType: 'com.pyom/termux_terminal_view',
      layoutDirection: TextDirection.ltr,
      creationParams: const <String, dynamic>{},
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: _onPlatformViewCreated,
    );
  }

  Widget _dot(Color c) => Container(
    width: 10, height: 10,
    decoration: BoxDecoration(color: c, shape: BoxShape.circle),
  );

  Widget _btn(IconData icon, String tooltip, VoidCallback onTap) =>
    Tooltip(message: tooltip,
      child: InkWell(onTap: onTap, borderRadius: BorderRadius.circular(4),
        child: Padding(padding: const EdgeInsets.all(5),
          child: Icon(icon, size: 14, color: _Mocha.subtext))));

  @override
  void dispose() {
    _viewChannel?.invokeMethod('kill').ignore();
    super.dispose();
  }
}
