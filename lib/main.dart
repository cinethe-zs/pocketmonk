import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'services/chat_provider.dart';
import 'services/llm_service.dart';
import 'services/model_manager.dart';
import 'theme/app_theme.dart';
import 'ui/chat_screen.dart';
import 'ui/model_setup_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PocketMonkApp());
}

class PocketMonkApp extends StatelessWidget {
  const PocketMonkApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => ModelManager(),
      child: MaterialApp(
        title:                      'PocketMonk',
        debugShowCheckedModeBanner: false,
        theme:                      AppTheme.dark,
        home:                       const _AppBootstrap(),
      ),
    );
  }
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

enum _BootState { loading, needsModel, ready }

class _AppBootstrap extends StatefulWidget {
  const _AppBootstrap();
  @override
  State<_AppBootstrap> createState() => _AppBootstrapState();
}

class _AppBootstrapState extends State<_AppBootstrap> {
  _BootState    _state       = _BootState.loading;
  LlmService?   _llmService;
  ChatProvider? _chatProvider;

  @override
  void initState() {
    super.initState();
    _boot();
  }

  Future<void> _boot() async {
    final mgr = context.read<ModelManager>();
    await mgr.init();

    final path = mgr.activeModelPath;
    if (path.isEmpty || !await File(path).exists()) {
      if (mounted) setState(() => _state = _BootState.needsModel);
      return;
    }

    await _initService(path, mgr);
  }

  Future<void> _initService(String modelPath, ModelManager mgr) async {
    final svc = LlmService();
    await svc.initialize(LlmConfig(modelPath: modelPath));

    final provider = ChatProvider(svc);
    await provider.init();

    _llmService  = svc;
    _chatProvider = provider;

    if (mounted) setState(() => _state = _BootState.ready);
  }

  /// Called from the setup screen after first download/selection.
  Future<void> _onModelReady(String modelPath) async {
    final mgr = context.read<ModelManager>();
    await mgr.setActiveModel(modelPath);
    await mgr.refresh();
    await _initService(modelPath, mgr);
  }

  /// Called from settings when the user switches to a different model.
  Future<void> _switchModel(String modelPath) async {
    final mgr = context.read<ModelManager>();
    await mgr.setActiveModel(modelPath);

    final newSvc = LlmService();
    await newSvc.initialize(LlmConfig(modelPath: modelPath));
    await _chatProvider!.switchModel(newSvc);
    _llmService = newSvc;
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return switch (_state) {
      _BootState.loading    => const _SplashScreen(),
      _BootState.needsModel => ModelSetupScreen(onModelReady: _onModelReady),
      _BootState.ready      => ChangeNotifierProvider.value(
          value: _chatProvider!,
          child: _ChatRoot(onSwitchModel: _switchModel),
        ),
    };
  }
}

// ── Chat root (injects callback for model switching) ──────────────────────────

class _ChatRoot extends StatelessWidget {
  final Future<void> Function(String) onSwitchModel;
  const _ChatRoot({required this.onSwitchModel});

  @override
  Widget build(BuildContext context) {
    return ChatScreen(onSwitchModel: onSwitchModel);
  }
}

// ── Splash ────────────────────────────────────────────────────────────────────

class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.self_improvement_rounded,
                size: 48, color: AppTheme.accent),
            SizedBox(height: 20),
            SizedBox(
              width: 24, height: 24,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: AppTheme.accent),
            ),
          ],
        ),
      ),
    );
  }
}
