import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'llm_service.dart';

const _kModelPathPref      = 'model_path';
const _kSecondaryModelPref = 'secondary_model_path';
const _kGpuLayersPref      = 'gpu_layers';

/// Single source of truth for all model catalogue data.
class ModelCatalogueEntry {
  final String name;
  final String subtitle;
  final String size;
  final String badge;
  final Color  badgeColor;
  final String filename;
  final String url;

  const ModelCatalogueEntry({
    required this.name,
    required this.subtitle,
    required this.size,
    required this.badge,
    required this.badgeColor,
    required this.filename,
    required this.url,
  });
}

const _accentColor      = Color(0xFF5B8EF0); // AppTheme.accent
const _greenBadgeColor  = Color(0xFF4CAF50);
const _orangeBadgeColor = Color(0xFFFF9800);

const modelCatalogue = [
  ModelCatalogueEntry(
    name:       'Gemma 3 4B',
    subtitle:   'Google · best quality · great chat & reasoning',
    size:       '~2.5 GB',
    badge:      'Recommended',
    badgeColor: _accentColor,
    filename:   'google_gemma-3-4b-it-Q4_K_M.gguf',
    url:        'https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf',
  ),
  ModelCatalogueEntry(
    name:       'Phi-4 Mini 3.8B',
    subtitle:   'Microsoft · strong reasoning · very fast',
    size:       '~2.5 GB',
    badge:      'Fast',
    badgeColor: _greenBadgeColor,
    filename:   'microsoft_Phi-4-mini-instruct-Q4_K_M.gguf',
    url:        'https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf',
  ),
  ModelCatalogueEntry(
    name:       'Qwen 2.5 3B',
    subtitle:   'Alibaba · lightest · minimal RAM usage',
    size:       '~1.9 GB',
    badge:      'Light',
    badgeColor: _orangeBadgeColor,
    filename:   'Qwen2.5-3B-Instruct-Q4_K_M.gguf',
    url:        'https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf',
  ),
];

enum DownloadStatus { idle, downloading, done, error }

class ModelManager extends ChangeNotifier {
  String       _activeModelPath    = '';
  String       _secondaryModelPath = '';
  int          _numGpuLayers       = 0;
  List<String> _downloadedPaths    = [];

  // Per-model download state
  final Map<String, DownloadStatus> _downloadStatus   = {};
  final Map<String, double>         _downloadProgress = {};
  final Map<String, String>         _downloadError    = {};
  final Map<String, http.Client>    _downloadClients  = {};

  String       get activeModelPath    => _activeModelPath;
  String       get secondaryModelPath => _secondaryModelPath;
  int          get numGpuLayers       => _numGpuLayers;
  bool         get hasSecondaryModel  =>
      _secondaryModelPath.isNotEmpty &&
      _secondaryModelPath != _activeModelPath;
  List<String> get downloadedPaths    => List.unmodifiable(_downloadedPaths);

  DownloadStatus downloadStatus(String filename) =>
      _downloadStatus[filename] ?? DownloadStatus.idle;
  double downloadProgress(String filename) =>
      _downloadProgress[filename] ?? 0.0;
  String downloadError(String filename) =>
      _downloadError[filename] ?? '';

  String _nameFor(String path) {
    if (path.isEmpty) return '';
    for (final e in modelCatalogue) {
      if (path.endsWith(e.filename)) return e.name;
    }
    return path.split('/').last;
  }

  String get activeModelName    => _nameFor(_activeModelPath);
  String get secondaryModelName => _nameFor(_secondaryModelPath);

  // ── Init ──────────────────────────────────────────────────────────────────

  Future<void> init() async {
    final prefs = await SharedPreferences.getInstance();
    _activeModelPath    = prefs.getString(_kModelPathPref)      ?? '';
    _secondaryModelPath = prefs.getString(_kSecondaryModelPref) ?? '';
    _numGpuLayers       = prefs.getInt(_kGpuLayersPref)         ?? 0;
    await _scanDownloaded();
    notifyListeners();
  }

  Future<void> _scanDownloaded() async {
    final dir = Directory(await LlmService.modelsDirectory());
    if (!await dir.exists()) return;
    _downloadedPaths = dir
        .listSync()
        .whereType<File>()
        .where((f) => f.path.endsWith('.gguf'))
        .map((f) => f.path)
        .toList();
  }

  // ── Model switching ───────────────────────────────────────────────────────

  Future<void> setActiveModel(String path) async {
    _activeModelPath = path;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kModelPathPref, path);
    notifyListeners();
  }

  Future<void> setSecondaryModel(String path) async {
    _secondaryModelPath = path;
    final prefs = await SharedPreferences.getInstance();
    if (path.isEmpty) {
      await prefs.remove(_kSecondaryModelPref);
    } else {
      await prefs.setString(_kSecondaryModelPref, path);
    }
    notifyListeners();
  }

  /// Swaps active ↔ secondary in preferences. Returns the new active path.
  Future<String> swapModels() async {
    if (_secondaryModelPath.isEmpty) return _activeModelPath;
    final newActive    = _secondaryModelPath;
    final newSecondary = _activeModelPath;
    _activeModelPath    = newActive;
    _secondaryModelPath = newSecondary;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kModelPathPref,      _activeModelPath);
    await prefs.setString(_kSecondaryModelPref, _secondaryModelPath);
    notifyListeners();
    return newActive;
  }

  // ── GPU layers ────────────────────────────────────────────────────────────

  Future<void> setNumGpuLayers(int layers) async {
    _numGpuLayers = layers;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_kGpuLayersPref, layers);
    notifyListeners();
  }

  // ── Download ──────────────────────────────────────────────────────────────

  Future<void> startDownload(ModelCatalogueEntry entry) async {
    if (_downloadStatus[entry.filename] == DownloadStatus.downloading) return;

    final path    = await LlmService.pathForModel(entry.filename);
    final tmpPath = '$path.download';
    final tmpFile = File(tmpPath);

    _downloadStatus[entry.filename]   = DownloadStatus.downloading;
    _downloadProgress[entry.filename] = 0.0;
    _downloadError[entry.filename]    = '';
    notifyListeners();

    IOSink? sink;
    try {
      await tmpFile.parent.create(recursive: true);
      final client   = http.Client();
      _downloadClients[entry.filename] = client;
      final response = await client.send(
          http.Request('GET', Uri.parse(entry.url)));

      if (response.statusCode != 200) {
        throw Exception('Server returned ${response.statusCode}');
      }

      final total = response.contentLength ?? 0;
      int received = 0;
      sink = tmpFile.openWrite();

      await for (final chunk in response.stream) {
        if (_downloadStatus[entry.filename] != DownloadStatus.downloading) break;
        sink.add(chunk);
        received += chunk.length;
        _downloadProgress[entry.filename] =
            total > 0 ? (received / total).clamp(0.0, 1.0) : 0.0;
        notifyListeners();
      }

      await sink.flush();
      await sink.close();
      sink = null;

      if (_downloadStatus[entry.filename] != DownloadStatus.downloading) {
        await tmpFile.delete().catchError((_) => tmpFile);
        return;
      }

      await tmpFile.rename(path);
      _downloadStatus[entry.filename]   = DownloadStatus.done;
      _downloadProgress[entry.filename] = 1.0;
      await _scanDownloaded();
      notifyListeners();
    } catch (e) {
      await sink?.close().catchError((_) {});
      await tmpFile.delete().catchError((_) => tmpFile);
      _downloadStatus[entry.filename] = DownloadStatus.error;
      _downloadError[entry.filename]  = e.toString();
      notifyListeners();
    } finally {
      _downloadClients.remove(entry.filename)?.close();
    }
  }

  void cancelDownload(String filename) {
    _downloadStatus[filename] = DownloadStatus.idle;
    _downloadClients.remove(filename)?.close();
    notifyListeners();
  }

  Future<void> deleteModel(String path) async {
    final file = File(path);
    if (await file.exists()) await file.delete();

    final prefs = await SharedPreferences.getInstance();
    if (_activeModelPath == path) {
      _activeModelPath = '';
      await prefs.remove(_kModelPathPref);
    }
    if (_secondaryModelPath == path) {
      _secondaryModelPath = '';
      await prefs.remove(_kSecondaryModelPref);
    }

    await _scanDownloaded();
    notifyListeners();
  }

  Future<void> refresh() async {
    await _scanDownloaded();
    notifyListeners();
  }
}
