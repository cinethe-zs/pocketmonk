import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

import '../services/llm_service.dart';
import '../theme/app_theme.dart';

// ── Model catalogue ───────────────────────────────────────────────────────────

class _ModelOption {
  final String name;
  final String subtitle;
  final String size;
  final String badge;
  final Color  badgeColor;
  final String filename;
  final String url;

  const _ModelOption({
    required this.name,
    required this.subtitle,
    required this.size,
    required this.badge,
    required this.badgeColor,
    required this.filename,
    required this.url,
  });
}

const _models = [
  _ModelOption(
    name:       'Gemma 3 4B',
    subtitle:   'Google · best quality · great chat & reasoning',
    size:       '~2.5 GB',
    badge:      'Recommended',
    badgeColor: AppTheme.accent,
    filename:   'google_gemma-3-4b-it-Q4_K_M.gguf',
    url:        'https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf',
  ),
  _ModelOption(
    name:       'Phi-4 Mini 3.8B',
    subtitle:   'Microsoft · strong reasoning · very fast',
    size:       '~2.5 GB',
    badge:      'Fast',
    badgeColor: Color(0xFF4CAF50),
    filename:   'microsoft_Phi-4-mini-instruct-Q4_K_M.gguf',
    url:        'https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf',
  ),
  _ModelOption(
    name:       'Qwen 2.5 3B',
    subtitle:   'Alibaba · lightest · minimal RAM usage',
    size:       '~1.9 GB',
    badge:      'Light',
    badgeColor: Color(0xFFFF9800),
    filename:   'qwen2.5-3b-instruct-q4_k_m.gguf',
    url:        'https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf',
  ),
];

// ── Download state ────────────────────────────────────────────────────────────

enum _DownloadState { idle, downloading, done, error }

// ── Screen ────────────────────────────────────────────────────────────────────

/// Shown on first launch (no model on device).
/// The user picks a model then downloads it, or copies one manually.
class ModelSetupScreen extends StatefulWidget {
  /// Called with the final model file path once it is ready to use.
  final Future<void> Function(String modelPath) onModelReady;

  const ModelSetupScreen({super.key, required this.onModelReady});

  @override
  State<ModelSetupScreen> createState() => _ModelSetupScreenState();
}

class _ModelSetupScreenState extends State<ModelSetupScreen> {
  // Model selection
  int _selectedIndex = 0;

  // Download
  _DownloadState _downloadState = _DownloadState.idle;
  int    _downloaded = 0;
  int    _total      = 0;
  String _errorMsg   = '';
  bool   _cancelled  = false;
  http.Client? _client;

  // Manual check
  bool _checking = false;

  // ── Helpers ──────────────────────────────────────────────────────────────

  _ModelOption get _selected => _models[_selectedIndex];

  Future<String> get _modelPath async =>
      LlmService.pathForModel(_selected.filename);

  String _fmtBytes(int bytes) {
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  double get _progress =>
      (_total > 0) ? (_downloaded / _total).clamp(0.0, 1.0) : 0.0;

  // ── Download ─────────────────────────────────────────────────────────────

  Future<void> _startDownload() async {
    final path = await _modelPath;
    setState(() {
      _downloadState = _DownloadState.downloading;
      _downloaded    = 0;
      _total         = 0;
      _cancelled     = false;
      _errorMsg      = '';
    });

    final tmpPath = '$path.download';
    final tmpFile = File(tmpPath);
    IOSink? sink;

    try {
      await tmpFile.parent.create(recursive: true);

      _client = http.Client();
      final request  = http.Request('GET', Uri.parse(_selected.url));
      final response = await _client!.send(request);

      if (response.statusCode != 200) {
        throw Exception('Server returned ${response.statusCode}');
      }

      _total = response.contentLength ?? 0;
      sink   = tmpFile.openWrite();

      await for (final chunk in response.stream) {
        if (_cancelled) break;
        sink.add(chunk);
        _downloaded += chunk.length;
        if (mounted) setState(() {});
      }

      await sink.flush();
      await sink.close();
      sink = null;

      if (_cancelled) {
        await tmpFile.delete().catchError((_) => tmpFile);
        if (mounted) setState(() => _downloadState = _DownloadState.idle);
        return;
      }

      await tmpFile.rename(path);

      if (mounted) {
        setState(() => _downloadState = _DownloadState.done);
        await Future.delayed(const Duration(milliseconds: 600));
        await widget.onModelReady(path);
      }
    } catch (e) {
      await sink?.close().catchError((_) {});
      await tmpFile.delete().catchError((_) => tmpFile);
      if (mounted) {
        setState(() {
          _downloadState = _DownloadState.error;
          _errorMsg = e.toString();
        });
      }
    } finally {
      _client?.close();
      _client = null;
    }
  }

  void _cancelDownload() {
    _cancelled = true;
    _client?.close();
  }

  // ── Manual check ─────────────────────────────────────────────────────────

  Future<void> _checkModel() async {
    setState(() => _checking = true);
    final path = await _modelPath;
    await Future.delayed(const Duration(milliseconds: 300));
    final exists = await File(path).exists();
    if (!mounted) return;
    setState(() => _checking = false);
    if (exists) {
      await widget.onModelReady(path);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('File not found:\n$path'),
          backgroundColor: AppTheme.error,
          duration: const Duration(seconds: 4),
        ),
      );
    }
  }

  // ── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final isDownloading = _downloadState == _DownloadState.downloading;

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [

              // ── Header ───────────────────────────────────────────────────
              Row(
                children: [
                  Container(
                    width: 52, height: 52,
                    decoration: BoxDecoration(
                      color: AppTheme.accentDim,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: const Icon(Icons.self_improvement_rounded,
                        color: AppTheme.accent, size: 28),
                  ),
                  const SizedBox(width: 14),
                  const Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('PocketMonk',
                          style: TextStyle(
                            color: AppTheme.textPrimary,
                            fontSize: 22,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.4,
                          )),
                      Text('Choose your AI model',
                          style: TextStyle(
                              color: AppTheme.textMuted, fontSize: 13)),
                    ],
                  ),
                ],
              ),

              const SizedBox(height: 32),

              // ── Model picker ─────────────────────────────────────────────
              const Text('Select a model',
                  style: TextStyle(
                      color: AppTheme.textSecondary,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.6)),

              const SizedBox(height: 10),

              ...List.generate(_models.length, (i) {
                final m = _models[i];
                final selected = i == _selectedIndex;
                return Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: GestureDetector(
                    onTap: isDownloading
                        ? null
                        : () => setState(() {
                              _selectedIndex  = i;
                              _downloadState = _DownloadState.idle;
                            }),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 180),
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: selected
                            ? AppTheme.accentDim
                            : AppTheme.surfaceRaised,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(
                          color:
                              selected ? AppTheme.accent : AppTheme.border,
                          width: selected ? 1.5 : 1,
                        ),
                      ),
                      child: Row(
                        children: [
                          // Radio dot
                          Container(
                            width: 18, height: 18,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(
                                color: selected
                                    ? AppTheme.accent
                                    : AppTheme.border,
                                width: 2,
                              ),
                              color: selected
                                  ? AppTheme.accent
                                  : Colors.transparent,
                            ),
                            child: selected
                                ? const Icon(Icons.check_rounded,
                                    size: 10, color: Colors.white)
                                : null,
                          ),
                          const SizedBox(width: 12),

                          // Info
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(m.name,
                                        style: TextStyle(
                                          color: selected
                                              ? AppTheme.textPrimary
                                              : AppTheme.textPrimary,
                                          fontSize: 14,
                                          fontWeight: FontWeight.w600,
                                        )),
                                    const SizedBox(width: 8),
                                    Container(
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 7, vertical: 2),
                                      decoration: BoxDecoration(
                                        color: m.badgeColor.withAlpha(30),
                                        borderRadius:
                                            BorderRadius.circular(5),
                                      ),
                                      child: Text(m.badge,
                                          style: TextStyle(
                                            color: m.badgeColor,
                                            fontSize: 10,
                                            fontWeight: FontWeight.w700,
                                          )),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 2),
                                Text(m.subtitle,
                                    style: const TextStyle(
                                        color: AppTheme.textMuted,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          const SizedBox(width: 8),

                          // Size
                          Text(m.size,
                              style: const TextStyle(
                                  color: AppTheme.textSecondary,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500)),
                        ],
                      ),
                    ),
                  ),
                );
              }),

              const SizedBox(height: 20),

              // ── Download card / progress ──────────────────────────────────
              _buildDownloadArea(),

              const SizedBox(height: 20),

              // ── Divider ───────────────────────────────────────────────────
              Row(
                children: [
                  const Expanded(child: Divider(color: AppTheme.border)),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Text('or copy manually',
                        style: const TextStyle(
                            color: AppTheme.textMuted, fontSize: 12)),
                  ),
                  const Expanded(child: Divider(color: AppTheme.border)),
                ],
              ),

              const SizedBox(height: 16),

              // ── Manual path display ───────────────────────────────────────
              const Text('Place the model file at:',
                  style: TextStyle(
                      color: AppTheme.textSecondary, fontSize: 13)),
              const SizedBox(height: 8),

              FutureBuilder<String>(
                future: _modelPath,
                builder: (context, snap) {
                  final path = snap.data ?? '…';
                  return GestureDetector(
                    onLongPress: () {
                      Clipboard.setData(ClipboardData(text: path));
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                            content: Text('Path copied to clipboard')),
                      );
                    },
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: AppTheme.surfaceRaised,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: AppTheme.border),
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(path,
                                style: const TextStyle(
                                  color: AppTheme.accent,
                                  fontSize: 11,
                                  fontFamily: 'monospace',
                                  height: 1.5,
                                )),
                          ),
                          const SizedBox(width: 8),
                          const Icon(Icons.copy_rounded,
                              size: 16, color: AppTheme.textMuted),
                        ],
                      ),
                    ),
                  );
                },
              ),

              const SizedBox(height: 20),

              // ── Already copied button ─────────────────────────────────────
              SizedBox(
                width: double.infinity,
                height: 48,
                child: OutlinedButton(
                  onPressed: (_checking || isDownloading) ? null : _checkModel,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppTheme.textSecondary,
                    side: const BorderSide(color: AppTheme.border),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14)),
                  ),
                  child: _checking
                      ? const SizedBox(
                          width: 18, height: 18,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: AppTheme.accent),
                        )
                      : const Text('I already copied it — continue',
                          style: TextStyle(fontSize: 13)),
                ),
              ),

              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDownloadArea() {
    return switch (_downloadState) {
      _DownloadState.idle => _buildIdleButton(),
      _DownloadState.downloading => _buildProgress(),
      _DownloadState.done => _buildDone(),
      _DownloadState.error => _buildError(),
    };
  }

  Widget _buildIdleButton() {
    return SizedBox(
      width: double.infinity,
      height: 54,
      child: ElevatedButton.icon(
        onPressed: _startDownload,
        icon:  const Icon(Icons.download_rounded, size: 20),
        label: Text('Download ${_selected.name} (${_selected.size})',
            style: const TextStyle(
                fontSize: 14, fontWeight: FontWeight.w600)),
        style: ElevatedButton.styleFrom(
          backgroundColor: AppTheme.accent,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14)),
          elevation: 0,
        ),
      ),
    );
  }

  Widget _buildProgress() {
    final pct      = (_progress * 100).toStringAsFixed(1);
    final dlStr    = _fmtBytes(_downloaded);
    final totalStr = _total > 0 ? _fmtBytes(_total) : '?';

    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const SizedBox(
                width: 16, height: 16,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: AppTheme.accent),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Downloading ${_selected.name}…',
                  style: const TextStyle(
                      color: AppTheme.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w600),
                ),
              ),
              Text('$dlStr / $totalStr',
                  style: const TextStyle(
                      color: AppTheme.textSecondary, fontSize: 12)),
            ],
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: _total > 0 ? _progress : null,
              backgroundColor: AppTheme.border,
              valueColor:
                  const AlwaysStoppedAnimation<Color>(AppTheme.accent),
              minHeight: 6,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _total > 0 ? '$pct%' : 'Connecting…',
                style: const TextStyle(
                    color: AppTheme.textMuted, fontSize: 12),
              ),
              GestureDetector(
                onTap: _cancelDownload,
                child: const Text('Cancel',
                    style: TextStyle(
                      color: AppTheme.error,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    )),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildDone() {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.success.withAlpha(120)),
      ),
      child: Row(
        children: [
          Container(
            width: 36, height: 36,
            decoration: BoxDecoration(
              color: AppTheme.success.withAlpha(30),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.check_rounded,
                color: AppTheme.success, size: 20),
          ),
          const SizedBox(width: 14),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Download complete',
                    style: TextStyle(
                        color: AppTheme.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w600)),
                SizedBox(height: 2),
                Text('Starting PocketMonk…',
                    style: TextStyle(
                        color: AppTheme.textSecondary, fontSize: 12)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildError() {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.error.withAlpha(120)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.error_outline_rounded,
                  color: AppTheme.error, size: 20),
              SizedBox(width: 10),
              Text('Download failed',
                  style: TextStyle(
                      color: AppTheme.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w600)),
            ],
          ),
          const SizedBox(height: 8),
          Text(_errorMsg,
              style: const TextStyle(
                  color: AppTheme.textMuted, fontSize: 11, height: 1.4),
              maxLines: 3,
              overflow: TextOverflow.ellipsis),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            height: 42,
            child: OutlinedButton.icon(
              onPressed: _startDownload,
              icon:  const Icon(Icons.refresh_rounded, size: 16),
              label: const Text('Retry', style: TextStyle(fontSize: 13)),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppTheme.textSecondary,
                side: const BorderSide(color: AppTheme.border),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
