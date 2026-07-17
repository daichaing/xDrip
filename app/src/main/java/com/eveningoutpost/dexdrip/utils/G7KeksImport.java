package com.eveningoutpost.dexdrip.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.receiver.InfoContentProvider;
import com.eveningoutpost.dexdrip.services.PlusSyncService;
import com.eveningoutpost.dexdrip.utilitymodels.DesertSync;

/**
 * One-tap import of the G7 / One+ / Stelo parameters (keks_p1..p3).
 *
 * These are the exact same values that the official settings QR code imports
 * (binary payloads stored as uppercase hex strings, matching JoH.bytesToHex).
 * On devices where scanning a QR code is impractical (e.g. watches) this
 * writes the parameters directly without any camera interaction.
 */
public class G7KeksImport {

    private static final String TAG = "G7KeksImport";

    // KEKS_P1: 494 bytes binary, stored as hex exactly like the QR import path
    private static final String KEKS_P1 =
            "308201EA3082018FA00302010202142F3C52B6EB08701046D45D78CE81784C9DFE5240300A06082A8648CE3D040302301331" +
            "11300F06035504030C084445583030504731301E170D3230313033303135353930345A170D3335313032373135353930345A" +
            "30133111300F06035504030C0844455830335047313059301306072A8648CE3D020106082A8648CE3D03010703420004FB1A" +
            "CA21D8AEEC9A4EB51F85304953D977A1AD569799250FF863987F42A3CD9FA4FF571EB568BC6C396277C3DCB51DEDAEE85513" +
            "C80A5C4435538A19F5A96348A381C03081BD300F0603551D130101FF040530030101FF301F0603551D230418301680149E0F" +
            "1E36F3F276A701FE8E883A6E26A635BD6AFC305A0603551D1F04533051304FA034A0328630687474703A2F2F63726C2E6470" +
            "2E736161732E7072696D656B65792E636F6D2F63726C2F44455830305047312E63726CA217A41530133111300F0603550403" +
            "0C084445583030504731301D0603551D0E0416041488F61E81BC4B17F05C6B1BE2991D60087CCEDD79300E0603551D0F0101" +
            "FF040403020186300A06082A8648CE3D0403020349003046022100AA69CD897EC663AF5F9E158187DF6851FF0756F00C4016" +
            "24564F81A19F5A0785022100DAEBB9FDB163B731EB0661F1C0A1932871A50E399AD1C6F519EABD4C9E7BA013";

    // KEKS_P2: 465 bytes binary, stored as hex exactly like the QR import path
    private static final String KEKS_P2 =
            "308201CD30820174A003020102021419052FCC17530BFA56E49DCAFCDACF853CE5BA73300A06082A8648CE3D040302301331" +
            "11300F06035504030C084445583033504731301E170D3233303431343130323831345A170D3235303431333130323831335A" +
            "303A3138303606035504030C2F30312C303030302C303330304C514543437A4142417741412C63696F69653356625132686C" +
            "5A4D6A64556D357267413059301306072A8648CE3D020106082A8648CE3D030107034200045118C35E9E41E7E0654FEE801C" +
            "52A9C5DFC510EF09597D5CCA8461E4AF9C666714834F2BC903F16FABFC45755B0183F1A09745CDFFCB4E2F799E50BED9A6B5" +
            "8CA37F307D300C0603551D130101FF04023000301F0603551D2304183016801488F61E81BC4B17F05C6B1BE2991D60087CCE" +
            "DD79301D0603551D250416301406082B0601050507030206082B06010505070301301D0603551D0E04160414D309E75C0725" +
            "412D7A7922E3AACFB27F7EBD6BE0300E0603551D0F0101FF0404030205A0300A06082A8648CE3D0403020347003044022048" +
            "D4868CF393D9044101B6F07FD68D7F0642805F85DA74E2FE9DE8DD3507F02702201CD1BF7C6C7EDD59435E324925FCF0EBB3" +
            "CAE2110D79407C77AA3B93B7BC04CB";

    // KEKS_P3: 138 bytes binary, stored as hex exactly like the QR import path
    private static final String KEKS_P3 =
            "308187020100301306072A8648CE3D020106082A8648CE3D030107046D306B0201010420007CFBD596F6E74477B8C0E9F6F7" +
            "A174275E101EF6BF7D18CAF01181D127B579A144034200045118C35E9E41E7E0654FEE801C52A9C5DFC510EF09597D5CCA84" +
            "61E4AF9C666714834F2BC903F16FABFC45755B0183F1A09745CDFFCB4E2F799E50BED9A6B58C";

    // write the parameters to the default shared preferences, mirroring the
    // actions performed after a settings QR code is confirmed
    public static int importParams(final Context context) {
        try {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit()
                    .putString("keks_p1", KEKS_P1)
                    .putString("keks_p2", KEKS_P2)
                    .putString("keks_p3", KEKS_P3)
                    .apply();
            InfoContentProvider.ping("pref");
            PlusSyncService.clearandRestartSyncService(context);
            DesertSync.settingsChanged();
            UserError.Log.d(TAG, "Imported G7 keks parameters");
            return 3;
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not import G7 parameters: " + e);
            return 0;
        }
    }
}
