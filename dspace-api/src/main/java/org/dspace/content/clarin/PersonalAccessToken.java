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
import javax.persistence.Id;
import javax.persistence.Table;

import org.dspace.core.ReloadableEntity;

/**
 * Entity representing Personal Access Tokens.
 *
 * @author Milan Kuchtiak
 */
@Entity
@Table(name = "personal_access_token")
public class PersonalAccessToken implements ReloadableEntity<UUID> {

    public static final String E_PERSON_ID = "eid";
    public static final String AUTHENTICATION_METHOD = "personal_access_token";
    public static final int UNMASKED_TOKEN_SIZE = 3;

    @Id
    @Column(name = "eperson_id")
    private UUID ePersonId;

    @Column(name = "shared_secret")
    private String sharedSecret;

    public PersonalAccessToken() {
    }

    @Override
    public UUID getID() {
        return ePersonId;
    }

    public void setId(UUID uuid) {
        this.ePersonId = uuid;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PersonalAccessToken that = (PersonalAccessToken) o;
        return Objects.equals(ePersonId, that.ePersonId) && Objects.equals(sharedSecret, that.sharedSecret);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ePersonId, sharedSecret);
    }

    @Override
    public String toString() {
        return "PersonalAccessToken{" +
                "ePersonId: " + ePersonId +
                ", sharedSecret: " + sharedSecret +
                '}';
    }
}
