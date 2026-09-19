package com.aicard.provider.azure;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig;
import com.microsoft.cognitiveservices.speech.PropertyId;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig;
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognitionResult;
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Azure Speech 适配器：LID（AutoDetectSourceLanguageConfig）、一步流式语音翻译（TranslationRecognizer
 * speech-to-speech，synthesizing 事件边翻译边流式合成语音）、ASR（SpeechRecognizer）、TTS（SpeechSynthesizer）。
 * 音频格式 PCM16 · 16kHz · 单声道。
 *
 * 注意：Azure LID 不返回置信度，detectLanguage 暂填 1.0（信任结果），软锁定低置信降级退化
 * （架构 spec 附录 B 技术风险 #1，联调实测后可换方案）。
 */
public class AzureSpeechProvider implements SpeechProvider {

    private static final long SAMPLE_RATE = 16000;
    private static final short BITS_PER_SAMPLE = 16;
    private static final short CHANNELS = 1;

    private final String key;
    private final String region;

    public AzureSpeechProvider(String key, String region) {
        this.key = key;
        this.region = region;
    }

    @Override
    public String name() { return "azure"; }

    @Override
    public LidResult detectLanguage(byte[] audio, List<String> candidates) {
        SpeechConfig config = SpeechConfig.fromSubscription(key, region);
        AutoDetectSourceLanguageConfig autoDetect = AutoDetectSourceLanguageConfig.fromLanguages(candidates);
        try (SpeechRecognizer recognizer = new SpeechRecognizer(config, autoDetect, fromPcm(audio))) {
            SpeechRecognitionResult result = recognizer.recognizeOnceAsync().get();
            String lang = result.getProperties()
                    .getProperty(PropertyId.SpeechServiceConnection_AutoDetectSourceLanguageResult);
            if (lang == null || lang.isBlank()) {
                lang = "ja-JP";
            }
            return new LidResult(lang, 1.0);
        } catch (Exception e) {
            throw new ProviderException("azure lid failed", e);
        }
    }

    @Override
    public SpeechTranslationResult translateToSpeech(byte[] audio, String src, String tgt, Consumer<byte[]> onAudioChunk) {
        SpeechTranslationConfig config = SpeechTranslationConfig.fromSubscription(key, region);
        config.setSpeechRecognitionLanguage(toLocale(src));
        config.addTargetLanguage(tgt);
        config.setVoiceName(voiceFor(tgt)); // speech-to-speech：翻译后合成 target 语音

        PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                AudioStreamFormat.getWaveFormatPCM(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS));
        AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);

        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicReference<String> translatedText = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (TranslationRecognizer recognizer = new TranslationRecognizer(config, audioConfig)) {
            // synthesizing 事件：边翻译边流式回调合成语音分片（首帧即回）
            recognizer.synthesizing.addEventListener((o, e) -> {
                byte[] chunk = e.getResult().getAudio();
                if (chunk != null && chunk.length > 0) {
                    onAudioChunk.accept(chunk);
                }
            });
            // recognized 事件：最终翻译文本
            recognizer.recognized.addEventListener((o, e) -> {
                TranslationRecognitionResult r = e.getResult();
                if (r.getReason() == ResultReason.TranslatedSpeech) {
                    finalText.set(r.getText());
                    translatedText.set(r.getTranslations().get(tgt));
                    done.countDown();
                }
            });

            recognizer.startContinuousRecognitionAsync().get();
            pushStream.write(audio);
            pushStream.close();
            done.await(30, TimeUnit.SECONDS);
            recognizer.stopContinuousRecognitionAsync().get();

            if (translatedText.get() == null) {
                throw new ProviderException("azure translation failed (no result)");
            }
            return new SpeechTranslationResult(finalText.get(), translatedText.get());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("azure translation failed", e);
        }
    }

    @Override
    public String transcribe(byte[] audio, String language) {
        SpeechConfig config = SpeechConfig.fromSubscription(key, region);
        config.setSpeechRecognitionLanguage(toLocale(language));
        try (SpeechRecognizer recognizer = new SpeechRecognizer(config, fromPcm(audio))) {
            SpeechRecognitionResult result = recognizer.recognizeOnceAsync().get();
            if (result.getReason() == ResultReason.RecognizedSpeech) {
                return result.getText();
            }
            throw new ProviderException("azure asr failed: " + result.getReason());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("azure asr failed", e);
        }
    }

    @Override
    public byte[] synthesize(String text, String language) {
        SpeechConfig config = SpeechConfig.fromSubscription(key, region);
        config.setSpeechSynthesisVoiceName(voiceFor(language));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpeechSynthesizer synthesizer = new SpeechSynthesizer(config, null)) {
            synthesizer.Synthesizing.addEventListener((o, e) -> {
                byte[] chunk = e.getResult().getAudioData();
                if (chunk != null && chunk.length > 0) {
                    out.writeBytes(chunk);
                }
            });
            synthesizer.SpeakTextAsync(text).get();
            // Synthesizing 事件累积的是多个完整 WAV 拼接（每个事件一个 WAV），
            // 剥离所有 WAV 头得到完整裸 PCM，再加一个标准 44 字节 WAV 头（与流式翻译 TTS 及规范 §2.2 一致）
            return addWavHeader(stripAllWavHeaders(out.toByteArray()));
        } catch (Exception e) {
            throw new ProviderException("azure tts failed", e);
        }
    }

    /** 剥离所有 WAV 头（Synthesizing 事件累积的是多个完整 WAV 拼接），拼接为裸 PCM。 */
    private static byte[] stripAllWavHeaders(byte[] audio) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int i = 0;
        while (i < audio.length - 4) {
            int riff = -1;
            for (int j = i; j < audio.length - 4; j++) {
                if (audio[j] == 'R' && audio[j + 1] == 'I' && audio[j + 2] == 'F' && audio[j + 3] == 'F') {
                    riff = j;
                    break;
                }
            }
            if (riff < 0) break;
            int data = -1;
            for (int j = riff + 12; j < audio.length - 4; j++) {
                if (audio[j] == 'd' && audio[j + 1] == 'a' && audio[j + 2] == 't' && audio[j + 3] == 'a') {
                    data = j;
                    break;
                }
            }
            if (data < 0) break;
            int pcmStart = data + 8;
            int nextRiff = audio.length;
            for (int j = pcmStart; j < audio.length - 4; j++) {
                if (audio[j] == 'R' && audio[j + 1] == 'I' && audio[j + 2] == 'F' && audio[j + 3] == 'F') {
                    nextRiff = j;
                    break;
                }
            }
            pcm.write(audio, pcmStart, nextRiff - pcmStart);
            i = nextRiff;
        }
        return pcm.toByteArray();
    }

    /** 给裸 PCM 加标准 44 字节 WAV 头（PCM16 · 16kHz · 单声道 · little-endian）。 */
    private static byte[] addWavHeader(byte[] pcm) {
        int dataSize = pcm.length;
        byte[] wav = new byte[dataSize + 44];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        int riffSize = dataSize + 36;
        wav[4] = (byte) (riffSize & 0xff); wav[5] = (byte) ((riffSize >> 8) & 0xff);
        wav[6] = (byte) ((riffSize >> 16) & 0xff); wav[7] = (byte) ((riffSize >> 24) & 0xff);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        wav[16] = 16; wav[17] = 0; wav[18] = 0; wav[19] = 0;
        wav[20] = 1; wav[21] = 0;
        wav[22] = 1; wav[23] = 0;
        wav[24] = (byte) (16000 & 0xff); wav[25] = (byte) ((16000 >> 8) & 0xff); wav[26] = 0; wav[27] = 0;
        wav[28] = (byte) (32000 & 0xff); wav[29] = (byte) ((32000 >> 8) & 0xff); wav[30] = 0; wav[31] = 0;
        wav[32] = 2; wav[33] = 0;
        wav[34] = 16; wav[35] = 0;
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        wav[40] = (byte) (dataSize & 0xff); wav[41] = (byte) ((dataSize >> 8) & 0xff);
        wav[42] = (byte) ((dataSize >> 16) & 0xff); wav[43] = (byte) ((dataSize >> 24) & 0xff);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    @Override
    public TurnSession startTurn(String src, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        SpeechTranslationConfig config = SpeechTranslationConfig.fromSubscription(key, region);
        config.setSpeechRecognitionLanguage(toLocale(src));
        config.addTargetLanguage(tgt);
        config.setVoiceName(voiceFor(tgt));

        PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                AudioStreamFormat.getWaveFormatPCM(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS));
        AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
        TranslationRecognizer recognizer = new TranslationRecognizer(config, audioConfig);

        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicReference<String> translatedText = new AtomicReference<>();
        AtomicReference<String> detectedLanguage = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        recognizer.synthesizing.addEventListener((o, e) -> {
            byte[] chunk = e.getResult().getAudio();
            if (chunk != null && chunk.length > 0) {
                onTtsAudioChunk.accept(chunk);
            }
        });
        recognizer.recognized.addEventListener((o, e) -> {
            TranslationRecognitionResult r = e.getResult();
            if (r.getReason() == ResultReason.TranslatedSpeech) {
                finalText.set(r.getText());
                translatedText.set(r.getTranslations().get(tgt));
                String detected = r.getProperties()
                        .getProperty(PropertyId.SpeechServiceConnection_AutoDetectSourceLanguageResult);
                if (detected != null && !detected.isBlank()) {
                    detectedLanguage.set(detected);
                }
                done.countDown();
            }
        });

        try {
            recognizer.startContinuousRecognitionAsync().get();
        } catch (Exception e) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
            throw new ProviderException("azure streaming start failed", e);
        }

        return new AzureTurnSession(pushStream, recognizer, finalText, translatedText, detectedLanguage, done);
    }

    @Override
    public TurnSession startTurnAuto(List<String> candidates, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        SpeechTranslationConfig config = SpeechTranslationConfig.fromSubscription(key, region);
        config.addTargetLanguage(tgt);
        config.setVoiceName(voiceFor(tgt));

        AutoDetectSourceLanguageConfig autoDetect = AutoDetectSourceLanguageConfig.fromLanguages(candidates);

        PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                AudioStreamFormat.getWaveFormatPCM(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS));
        AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
        TranslationRecognizer recognizer = new TranslationRecognizer(config, autoDetect, audioConfig);

        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicReference<String> translatedText = new AtomicReference<>();
        AtomicReference<String> detectedLanguage = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        recognizer.synthesizing.addEventListener((o, e) -> {
            byte[] chunk = e.getResult().getAudio();
            if (chunk != null && chunk.length > 0) {
                onTtsAudioChunk.accept(chunk);
            }
        });
        recognizer.recognized.addEventListener((o, e) -> {
            TranslationRecognitionResult r = e.getResult();
            if (r.getReason() == ResultReason.TranslatedSpeech) {
                finalText.set(r.getText());
                translatedText.set(r.getTranslations().get(tgt));
                String detected = r.getProperties()
                        .getProperty(PropertyId.SpeechServiceConnection_AutoDetectSourceLanguageResult);
                if (detected != null && !detected.isBlank()) {
                    detectedLanguage.set(detected);
                }
                done.countDown();
            }
        });

        try {
            recognizer.startContinuousRecognitionAsync().get();
        } catch (Exception e) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
            throw new ProviderException("azure streaming start failed", e);
        }

        return new AzureTurnSession(pushStream, recognizer, finalText, translatedText, detectedLanguage, done);
    }

    /** 流式 turn：跨多次 push 复用同一 recognizer；finish 关流收口，cancel 打断清理。 */
    private static final class AzureTurnSession implements TurnSession {

        private final PushAudioInputStream pushStream;
        private final TranslationRecognizer recognizer;
        private final AtomicReference<String> finalText;
        private final AtomicReference<String> translatedText;
        private final AtomicReference<String> detectedLanguage;
        private final CountDownLatch done;

        AzureTurnSession(PushAudioInputStream pushStream, TranslationRecognizer recognizer,
                         AtomicReference<String> finalText, AtomicReference<String> translatedText,
                         AtomicReference<String> detectedLanguage, CountDownLatch done) {
            this.pushStream = pushStream;
            this.recognizer = recognizer;
            this.finalText = finalText;
            this.translatedText = translatedText;
            this.detectedLanguage = detectedLanguage;
            this.done = done;
        }

        @Override
        public void push(byte[] audioChunk) {
            try {
                pushStream.write(audioChunk);
            } catch (Exception e) {
                throw new ProviderException("azure streaming push failed", e);
            }
        }

        @Override
        public SpeechTranslationResult finish() {
            try {
                pushStream.close();
                if (!done.await(30, TimeUnit.SECONDS)) {
                    throw new ProviderException("azure streaming translate timeout");
                }
                recognizer.stopContinuousRecognitionAsync().get();
                String t = translatedText.get();
                if (t == null) {
                    throw new ProviderException("azure streaming translate failed (no result)");
                }
                return new SpeechTranslationResult(finalText.get(), t, detectedLanguage.get());
            } catch (ProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new ProviderException("azure streaming translate failed", e);
            } finally {
                try {
                    recognizer.close();
                } catch (Exception ignored) {
                    // 关闭识别器异常可忽略
                }
            }
        }

        @Override
        public void cancel() {
            try {
                recognizer.stopContinuousRecognitionAsync().get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 打断时忽略停流异常
            } finally {
                try {
                    recognizer.close();
                } catch (Exception ignored) {
                    // 关闭识别器异常可忽略
                }
                try {
                    pushStream.close();
                } catch (Exception ignored) {
                    // 流可能已关
                }
            }
        }
    }

    private static AudioConfig fromPcm(byte[] audio) {
        AudioStreamFormat format = AudioStreamFormat.getWaveFormatPCM(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS);
        PushAudioInputStream pushStream = AudioInputStream.createPushStream(format);
        AudioConfig config = AudioConfig.fromStreamInput(pushStream);
        pushStream.write(audio);
        pushStream.close();
        return config;
    }

    private static String voiceFor(String lang) {
        if (lang == null) return "ja-JP-NanamiNeural";
        return switch (lang) {
            case "zh-CN", "zh" -> "zh-CN-XiaoxiaoNeural";
            case "ja-JP", "ja" -> "ja-JP-NanamiNeural";
            case "en-US", "en" -> "en-US-JennyNeural";
            case "ko-KR", "ko" -> "ko-KR-SunHiNeural";
            default -> "ja-JP-NanamiNeural";
        };
    }

    /** 语音识别语言需要完整 locale（lang_pair 短码映射）。 */
    private static String toLocale(String lang) {
        if (lang == null) return lang;
        return switch (lang) {
            case "zh" -> "zh-CN";
            case "ja" -> "ja-JP";
            case "en" -> "en-US";
            case "ko" -> "ko-KR";
            default -> lang;
        };
    }
}
