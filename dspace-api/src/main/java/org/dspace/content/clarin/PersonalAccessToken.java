/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.util.Objects;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import com.nimbusds.jose.JOSEObjectType;
import org.dspace.core.ReloadableEntity;

/**
 * Entity representing Personal Access Tokens.
 *
 * @author Milan Kuchtiak
 */
@Entity
@Table(name = "personal_access_token")
public class PersonalAccessToken implements ReloadableEntity<Integer> {

    public static final String E_PERSON_ID = "eid";
    public static final String TOKEN_ISSUER = "clarin-dspace";
    public static final JOSEObjectType JWE_TOKEN_CLARIN_TYPE = new JOSEObjectType("JWE-CLARIN");
    public static final int UNMASKED_TOKEN_SIZE = 3;

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "personal_access_token_id_seq")
    @SequenceGenerator(name = "personal_access_token_id_seq", sequenceName = "personal_access_token_id_seq",
            allocationSize = 1)
    private Integer id;

    @Column(name = "eperson_id")
    private UUID ePersonID;

    @Column(name = "mac_secret")
    private String macSecret;

    @Column(name = "aes_key")
    private String aesKey;

    public PersonalAccessToken() {
    }

    @Override
    public Integer getID() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public UUID getEPersonID() {
        return ePersonID;
    }

    public void setEPersonID(UUID ePersonID) {
        this.ePersonID = ePersonID;
    }

    /**
     * Returns a MAC (Message Authentication Code) shared secret key used to verify token
     *
     * @return MAC sharedSecret value
     */
    public String getMacSecret() {
        return macSecret;
    }

    public void setMacSecret(String macSecret) {
        this.macSecret = macSecret;
    }

    /**
     * Returns a symmetric AES key (Advanced Encryption Standard key) used for Direct JWE encryption/decryption.
     *
     * @return AES decryption key
     */
    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PersonalAccessToken that = (PersonalAccessToken) o;
        return Objects.equals(ePersonID, that.ePersonID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ePersonID);
    }

    @Override
    public String toString() {
        return "PersonalAccessToken{" +
                "id: " + id +
                ", ePersonId: " + ePersonID +
                ", macSecret: " + macSecret +
                ", aesKey: " + aesKey +
                '}';
    }
}
