package org.playacl

trait AclObject

/**
 * Acl Component using roles and identities to check if a resource/privilege
 * is allowed for the current defined identity.
 *
 * Usage:
 *     Acl ++ org.playacl.Role - to add a role and the right definition to the acl component
 *     Acl ++ org.playacl.Role1 ++ org.playacl.Role2
 *
 *     Acl += org.playacl.Identity - to add the current identity to the acl
 *     This identity and it's containing roles will be used for allowed checks
 *
 * How it works:
 *     the roleRegistry is used to store all defined roles and to map the identity-roles to a role object.
 *     The identity itself will store only integer values as bits (1,2,4,8 ...) which maps to the role
 *     identifier.
 *
 *     Example:
 *         Role1.id=1, Role2.id=2 ... Role4.id=8
 *         Identity:roles = 11 means List(Role1, Role2, Role8)
 *
 *     While adding a identity, the mapping will check if there is a role defined, generates a new unique
 *     GenericRole which inherits from all mapped Roles.
 *
 *     Resource:
 *         Resources have to be objects / case classes. It's more used like a type
 *
 *     Privilege:
 *         Privileges are also just types as resources are
 *
 *     Assert:
 *         An assert is a function which receive "Option[AclObject]" as it's first parameter and return always a boolean.
 *         This type or assertion can be used to decide on "AclObject" if the resource/privilege is allowed or not.
 *
 *         Example:
 *             Lets say you have a site which have a company profile and related employees (Identities).
 *             Companies and Employees are in relation and you want to allow some employees to change things
 *             on your company profile page. So some employees will have a flag (canEditCompany).
 *             Your assert can now receive the company entity and you can check if the user is related to
 *             the company and is allowed to edit.
 *
 *     Roles:
 *         A role defines the rule definition???? and the resources, privileges and asserts. You can reduce it to
 *         just resources when you want to allow every privilege in this resource (helpful for admins).
 *
 *         Mapping example:
 *             val rules = scala.collection.mutable.Map(
 *                 Resource -> scala.collection.mutable.Map() <- allowed all privileges
 *                 Resource2 -> scala.collection.mutable.Map(
 *                     Privilege1 -> Seq(), <- no assertions
 *                     Privilege2 -> Seq((value: Option[AclObject]) => true|false)
 *                 )
 *             )
 *
 *     Let's see some implementations
 *
 *     Examples:
 *     Acl.isAllowed(Resource, Privilege)
 *     Acl.isAllowed(Resource, Privilege, Some(Foo))
 *
 */
case class Acl[I <: Identity](roles: List[Role], user: I) {


	/**
	 * available roles
	 */
	lazy val roleRegistry: List[Role] = roles

	/**
	 * rule definitions
	 */
	lazy val rules: Map[String, Seq[Option[AclObject] => Boolean]] = applyRules(roles)

	/**
	 * the role of the observer itself
	 */
	lazy val observerRole: Role = generateGenericRole(user)

	/**
	 * the observer (Identity)
	 */
	def observerEntity: I = user

	/**
	 * create the custom role for the current user
	 */
	def generateGenericRole(user: I) = {

		val inheritedRoles = roleRegistry.filter(role => (user.roles & role.getIdentifier) == role.getIdentifier)
		GenericRole("user_role_%d" format user.id, inheritedRoles)
	}

	/**
	 * apply the rule definition from a role to a easy understandable format for the acl to
	 * check against
	 */
	def applyRules(roles: List[Role]): Map[String, Seq[Option[AclObject] => Boolean]] = {

		val convertedRules = for {
			role <- roles
			rules <- role.getPrivileges
			roleRules <- rules._2
		} yield createHash(role, rules._1, roleRules._1) -> roleRules._2

		convertedRules.toMap
	}

	/**
	 * creates a hash value by give role, resource and privilege
	 * RoleName/ResourceName/PrivilegeName
	 * RoleName/ResourceName/\* for allowing all privileges in this resource
	 */
	def createHash(role: Role, r: Resource, p: Privilege): String = {

		"%s/%s/%s" format (role.getRoleId, r.name, p.name)
	}

	/**
	 * check methods if a resource and a privilege is allowed by itself or with object
	 */
	def isAllowed(r: Resource, p: Privilege, o: Option[AclObject] = None): Boolean = {

		allowed(observerRole, r, p, o)
	}

	/**
	 * internal, recursive check method.
	 * 1. check if a resource is allowed including all privileges
	 * 2. check if a resource is allowed for given privilege
	 * 3. check all inheritance roles by using recursion
	 */
	def allowed(role: Role, resource: Resource, privilege: Privilege, objectToCheck: Option[AclObject]): Boolean = {

		def checkForRole(roleToCheck: Role): Boolean = {

			val hash = createHash(roleToCheck, resource, privilege)

			def assertions: List[Boolean] = rules.collect{
				case (`hash`, definition) => definition.map(_ apply objectToCheck)
			}.toList.flatten

			rules.contains(hash) && !assertions.contains(false)
		}

		val currentAccess = checkForRole(role)
		def inheritedRoles = role.getInheritedRoles.map(allowed(_, resource, privilege, objectToCheck))

		currentAccess || inheritedRoles.contains(true)
	}
}