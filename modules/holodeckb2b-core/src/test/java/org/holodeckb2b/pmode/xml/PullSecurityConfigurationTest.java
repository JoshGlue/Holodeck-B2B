/**
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.pmode.xml;

import java.io.File;
import org.holodeckb2b.interfaces.pmode.ISigningConfiguration;
import org.holodeckb2b.interfaces.pmode.IUsernameTokenConfiguration;
import org.holodeckb2b.interfaces.security.SecurityHeaderTarget;
import org.holodeckb2b.interfaces.security.UTPasswordType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import org.junit.Test;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

/**
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class PullSecurityConfigurationTest {

    public PullSecurityConfigurationTest() {
    }

    private PullSecurityConfiguration createFromFile(final String fName) throws Exception {

        try {
            // retrieve the resource from the pmodetest directory.
            final File f = new File(this.getClass().getClassLoader().getResource("pmodetest/pullsec/" + fName).getPath());

            final Serializer  serializer = new Persister();
            return serializer.read(PullSecurityConfiguration.class, f);
        }
        catch (final Exception ex) {
            System.out.println("Exception '" + ex.getLocalizedMessage() + "'");
            return null;
        }
    }

    @Test
    public void testCompleteConfig() {
        try {
            final SecurityConfiguration sc = createFromFile("full_SC.xml");

            assertNotNull(sc);

            // Check the ebms UT
            final IUsernameTokenConfiguration ebmsUT =
                                sc.getUsernameTokenConfiguration(SecurityHeaderTarget.EBMS);
            assertNotNull(ebmsUT);
            assertEquals("DmTnJLxXFgEsuQX", ebmsUT.getUsername());
            assertEquals("ymjl8hCo0BdTvcRf", ebmsUT.getPassword());
            assertEquals(UTPasswordType.TEXT, ebmsUT.getPasswordType());
            assertFalse(ebmsUT.includeNonce());
            assertFalse(ebmsUT.includeCreated());

            // Check the Signature config
            final ISigningConfiguration signatureCfg = sc.getSignatureConfiguration();
            assertNotNull(signatureCfg);
            assertEquals("KeystoreAlias2" ,signatureCfg.getKeystoreAlias());
            assertEquals("http://www.w3.org/2001/04/xmldsig-more#sha384", signatureCfg.getHashFunction());
        } catch (final Exception e) {
            fail();
        }

    }

    @Test
    public void testMoreThanOneUT() {
        try {
            final SecurityConfiguration sc = createFromFile("twoUT_SC.xml");

            assertNull(sc);
        } catch (final Exception e) {
            fail();
        }
    }

    @Test
    public void testOnlyUT() {
        try {
            final SecurityConfiguration sc = createFromFile("onlyUT_SC.xml");

            assertNotNull(sc);

            // Check the ebms UT
            final IUsernameTokenConfiguration ebmsUT =
                                sc.getUsernameTokenConfiguration(SecurityHeaderTarget.EBMS);
            assertNotNull(ebmsUT);
            assertEquals("DmTnJLxXFgEsuQX", ebmsUT.getUsername());
            assertEquals("ymjl8hCo0BdTvcRf", ebmsUT.getPassword());
            assertEquals(UTPasswordType.TEXT, ebmsUT.getPasswordType());
            assertFalse(ebmsUT.includeNonce());
            assertFalse(ebmsUT.includeCreated());

            // Check no signing
            assertNull(sc.getSignatureConfiguration());

        } catch (final Exception e) {
            fail();
        }
    }

    @Test
    public void testSignatureOnly() {
        try {
            final SecurityConfiguration sc = createFromFile("signatureOnlySC.xml");

            assertNotNull(sc);

            // Check that there are no UT
            assertNull(sc.getUsernameTokenConfiguration(SecurityHeaderTarget.EBMS));
            assertNull(sc.getUsernameTokenConfiguration(SecurityHeaderTarget.DEFAULT));

            // Check the Signature config
            final ISigningConfiguration signatureCfg = sc.getSignatureConfiguration();
            assertNotNull(signatureCfg);
            assertEquals("KeystoreAlias3" ,signatureCfg.getKeystoreAlias());
            assertEquals("http://www.w3.org/2001/04/xmldsig-more#sha384", signatureCfg.getHashFunction());
        } catch (final Exception e) {
            fail();
        }
    }
}
