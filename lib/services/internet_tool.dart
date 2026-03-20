import 'dart:convert';

import 'package:fllama/fllama.dart';
import 'package:http/http.dart' as http;

/// Tool: search the web via DuckDuckGo Instant Answer API.
final webSearchTool = Tool(
  name: 'web_search',
  description:
      'Searches the web and returns a concise summary of the top results. '
      'Use this when the user asks about recent events, facts you are unsure about, '
      'prices, documentation, or anything that benefits from up-to-date information.',
  jsonSchema: jsonEncode({
    'type': 'object',
    'properties': {
      'query': {
        'type': 'string',
        'description': 'The search query to look up.',
      },
    },
    'required': ['query'],
  }),
);

/// Tool: fetch and read the text content of a URL.
final fetchUrlTool = Tool(
  name: 'fetch_url',
  description:
      'Downloads a web page and returns its plain-text content (HTML stripped). '
      'Use this to read documentation, articles, or any specific URL the user provides.',
  jsonSchema: jsonEncode({
    'type': 'object',
    'properties': {
      'url': {
        'type': 'string',
        'description': 'The full URL (including https://) to fetch.',
      },
    },
    'required': ['url'],
  }),
);

/// Executes the web_search tool using DuckDuckGo Instant Answer API.
Future<String> executeWebSearch(Map<String, dynamic> args) async {
  final query = args['query'] as String? ?? '';
  if (query.isEmpty) return 'Error: empty query.';

  try {
    final uri = Uri.parse('https://api.duckduckgo.com/').replace(
      queryParameters: {
        'q':               query,
        'format':          'json',
        'no_html':         '1',
        'skip_disambig':   '1',
        'no_redirect':     '1',
      },
    );
    final resp = await http.get(uri).timeout(const Duration(seconds: 10));
    if (resp.statusCode != 200) {
      return 'Web search failed (HTTP ${resp.statusCode}).';
    }

    final data = jsonDecode(resp.body) as Map<String, dynamic>;
    final buffer = StringBuffer();

    final abstract_ = (data['Abstract'] as String? ?? '').trim();
    final abstractSource = (data['AbstractSource'] as String? ?? '').trim();
    final abstractUrl = (data['AbstractURL'] as String? ?? '').trim();

    if (abstract_.isNotEmpty) {
      buffer.writeln('**$abstractSource**: $abstract_');
      if (abstractUrl.isNotEmpty) buffer.writeln('Source: $abstractUrl');
      buffer.writeln();
    }

    final answer = (data['Answer'] as String? ?? '').trim();
    if (answer.isNotEmpty) {
      buffer.writeln('**Answer**: $answer');
      buffer.writeln();
    }

    final topics = data['RelatedTopics'] as List? ?? [];
    int count = 0;
    for (final t in topics) {
      if (count >= 5) break;
      if (t is! Map) continue;
      final text = (t['Text'] as String? ?? '').trim();
      final url  = (t['FirstURL'] as String? ?? '').trim();
      if (text.isEmpty) continue;
      buffer.writeln('- $text');
      if (url.isNotEmpty) buffer.writeln('  $url');
      count++;
    }

    if (buffer.isEmpty) {
      return 'No results found for: "$query". Try a different search query.';
    }
    return buffer.toString().trim();
  } catch (e) {
    return 'Web search error: $e';
  }
}

/// Executes the fetch_url tool — downloads a page and strips HTML.
Future<String> executeFetchUrl(Map<String, dynamic> args) async {
  final url = args['url'] as String? ?? '';
  if (url.isEmpty) return 'Error: no URL provided.';

  try {
    final uri = Uri.parse(url);
    final resp = await http.get(uri, headers: {
      'User-Agent': 'Mozilla/5.0 (compatible; PocketMonk/1.0)',
    }).timeout(const Duration(seconds: 15));

    if (resp.statusCode != 200) {
      return 'Fetch failed (HTTP ${resp.statusCode}) for $url';
    }

    final text = _stripHtml(resp.body);
    const maxLen = 3000;
    return text.length > maxLen ? '${text.substring(0, maxLen)}\n[... content truncated]' : text;
  } catch (e) {
    return 'Fetch error for $url: $e';
  }
}

/// Strips HTML tags and collapses whitespace.
String _stripHtml(String html) {
  // Remove script/style blocks
  var text = html
      .replaceAll(RegExp(r'<script[^>]*>[\s\S]*?</script>', caseSensitive: false), ' ')
      .replaceAll(RegExp(r'<style[^>]*>[\s\S]*?</style>',   caseSensitive: false), ' ')
      .replaceAll(RegExp(r'<[^>]+>'), ' ')
      .replaceAll(RegExp(r'&nbsp;', caseSensitive: false), ' ')
      .replaceAll(RegExp(r'&amp;',  caseSensitive: false), '&')
      .replaceAll(RegExp(r'&lt;',   caseSensitive: false), '<')
      .replaceAll(RegExp(r'&gt;',   caseSensitive: false), '>')
      .replaceAll(RegExp(r'&quot;', caseSensitive: false), '"')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  return text;
}
