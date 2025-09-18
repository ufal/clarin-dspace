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
 * Entity representing Clarin Tokens.
 *
 * @author Milan Kuchtiak
 */
@Entity
@Table(name = "clarin_token")
public class ClarinToken implements ReloadableEntity<Integer> {

    public static final String E_PERSON_ID = "eid";
    public static final String TOKEN_ISSUER = "clarin-dspace";
    public static final JOSEObjectType TOKEN_TYPE = new JOSEObjectType("CLARIN-JWE-TOKEN");
    public static final int MASKED_TOKEN_SIZE = 15;
    public static final int UNMASKED_TOKEN_SIZE = 3;
    public static final String PROPERTY_ENCRYPTION_SECRET = "clarin.token.encryption.secret";

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "clarin_token_id_seq")
    @SequenceGenerator(name = "clarin_token_id_seq", sequenceName = "clarin_token_id_seq",
            allocationSize = 1)
    private Integer id;

    @Column(name = "eperson_id")
    private UUID ePersonID;

    @Column(name = "sign_key")
    private String signKey;

    public ClarinToken() {
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
     * Returns a MAC (Message Authentication Code) shared secret key used to sign and verify token
     *
     * @return sharedSecret value
     */
    public String getSignKey() {
        return signKey;
    }

    public void setSignKey(String signKey) {
        this.signKey = signKey;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClarinToken that = (ClarinToken) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(ePersonID, that.ePersonID) &&
                Objects.equals(signKey, that.signKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ePersonID, signKey);
    }

    @Override
    public String toString() {
        return "ClarinToken{" +
                "id: " + id +
                ", ePersonId: " + ePersonID +
                ", signKey: " + signKey +
                '}';
    }
}
