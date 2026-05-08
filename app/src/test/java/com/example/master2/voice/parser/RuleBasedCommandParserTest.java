package com.example.master2.voice.parser;

import com.example.master2.voice.model.ScheduleSpec;
import com.example.master2.voice.model.VoiceCommandIntent;

import org.junit.Assert;
import org.junit.Test;

public class RuleBasedCommandParserTest {
    private final RuleBasedCommandParser parser = new RuleBasedCommandParser();

    @Test
    public void parsesMultiAppBlock() {
        VoiceCommandIntent intent = parser.parse("block youtube and instagram");
        Assert.assertTrue(intent.valid);
        Assert.assertEquals(VoiceCommandIntent.ACTION_BLOCK, intent.action);
        Assert.assertEquals(ScheduleSpec.TYPE_IMMEDIATE, intent.scheduleSpec.scheduleType);
        Assert.assertEquals(2, intent.appAliases.size());
    }

    @Test
    public void parsesAfterDelay() {
        VoiceCommandIntent intent = parser.parse("block instagram after 30 min");
        Assert.assertTrue(intent.valid);
        Assert.assertEquals(ScheduleSpec.TYPE_AFTER_DURATION, intent.scheduleSpec.scheduleType);
        Assert.assertTrue(intent.scheduleSpec.delayMs >= 30L * 60L * 1000L);
    }

    @Test
    public void parsesHinglishRange() {
        VoiceCommandIntent intent = parser.parse("whatsapp ko raat 10 se subah 7 tak block karo");
        Assert.assertTrue(intent.valid);
        Assert.assertEquals(VoiceCommandIntent.ACTION_BLOCK, intent.action);
        Assert.assertEquals(ScheduleSpec.TYPE_TIME_RANGE, intent.scheduleSpec.scheduleType);
        Assert.assertTrue(intent.scheduleSpec.endEpochMs > intent.scheduleSpec.startEpochMs);
    }

    @Test
    public void parsesShortAliases() {
        VoiceCommandIntent intent = parser.parse("insta aur yt block karo");
        Assert.assertTrue(intent.valid);
        Assert.assertTrue(intent.appAliases.contains("insta"));
        Assert.assertTrue(intent.appAliases.contains("yt"));
    }
}
