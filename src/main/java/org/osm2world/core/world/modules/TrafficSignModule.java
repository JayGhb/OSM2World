package org.osm2world.core.world.modules;

import static java.lang.Math.PI;
import static java.util.Arrays.asList;
import static org.osm2world.core.math.VectorXYZ.X_UNIT;
import static org.osm2world.core.target.common.material.Materials.*;
import static org.osm2world.core.target.common.material.NamedTexCoordFunction.STRIP_FIT;
import static org.osm2world.core.target.common.material.TexCoordUtil.texCoordLists;
import static org.osm2world.core.world.modules.common.WorldModuleParseUtil.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osm2world.core.map_data.data.MapNode;
import org.osm2world.core.map_data.data.MapElement;
import org.osm2world.core.map_data.data.MapWaySegment;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.target.RenderableToAllTargets;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.TextTextureData;
import org.osm2world.core.target.common.TextureData;
import org.osm2world.core.target.common.material.ConfMaterial;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Material.Interpolation;
import org.osm2world.core.world.data.NoOutlineNodeWorldObject;
import org.osm2world.core.world.modules.common.AbstractModule;
import org.osm2world.core.world.modules.common.TrafficSignType;


/**
 * adds traffic signs to the world
 */
public class TrafficSignModule extends AbstractModule {

	@Override
	protected void applyToNode(MapNode node) {

		if (!node.getTags().containsKey("traffic_sign")) return;

		if (isInHighway(node)) return; //only exact positions (next to highway)

		String tagValue = node.getTags().getValue("traffic_sign");

		String country = "";
		String signs[];
		TrafficSignType attributes;

		/* split the traffic sign value into its components */

		if(tagValue.contains(":")) {

			//if country prefix is used
			String[] countryAndSigns = tagValue.split(":", 2);
			if(countryAndSigns.length !=2) return;
			country = countryAndSigns[0];
			signs = countryAndSigns[1].split("[;,]");

		} else {

			//human-readable value
			signs = tagValue.split("[;,]");
		}

		List<TrafficSignType> types = new ArrayList<TrafficSignType>(signs.length);

		String regex = null;
		Pattern pattern = null;
		Matcher matcher1 = null; //the matcher to match traffic sign values in .osm file

		for (String sign : signs) {

			//re-initialize the values below for every sign
			ConfMaterial originalMaterial = null;
			HashMap<String, String> map = new HashMap<String, String>();

			sign.trim();
			sign = sign.replace('-', '_');

			/* extract subtype and/or brackettext values */

			regex = "[A-Za-z0-9]*\\.?\\d*_?(\\d+)?(?:\\[(.*)\\])?"; //match every traffic sign
			pattern = Pattern.compile(regex);
			matcher1 = pattern.matcher(sign);

			if(matcher1.matches()) {

				if(matcher1.group(1)!=null) {
					map.put("traffic_sign.subtype", matcher1.group(1));
				}

				if(matcher1.group(2)!=null) {

					String brackettext = matcher1.group(2).replace('_', '-'); //revert the previous replacement
					map.put("traffic_sign.brackettext", brackettext);

					//cut off value to get the actual sign name
					sign = sign.replace("["+matcher1.group(2)+"]", "");
				}

			}

			sign = sign.toUpperCase();

			String signName = generateSignName(country, sign);

			attributes = mapSignAttributes(signName);

			/* "Fallback" functionality in case no attributes and material are defined for this sign name
			 *
			 * attributes.materialName.isEmpty() -> no "trafficSign_name_material = " defined for this sign
			 * getMaterial(signName)==null -> no material defined for this sign name
			 * map.containsKey("traffic_sign.subtype") -> sign name contains a subtype value (e.g. 50 in 274-50)
			 */
			if(attributes.materialName.isEmpty() && getMaterial(signName)==null && map.containsKey("traffic_sign.subtype")) {

				sign = sign.replace("_"+matcher1.group(1), ""); //cut off subtype value

				signName = generateSignName(country, sign);

				attributes = mapSignAttributes(signName); //retry with new sign name
			}

			if(!attributes.materialName.isEmpty()) {
				originalMaterial = getMaterial(attributes.materialName);
				attributes.material = configureMaterial(originalMaterial, map, node);
			}

			if(attributes.material==null) {

				//if there is no material configured for the sign, try predefined ones
				originalMaterial = getMaterial(signName);
				attributes.material = configureMaterial(originalMaterial, map, node);

				//if there is no material defined for the sign, create simple white sign
				if(attributes.material==null) {
					attributes.material = new ConfMaterial(Interpolation.FLAT, Color.white);
				}
			}

			if(attributes.defaultHeight==0) attributes.defaultHeight = config.getFloat("defaultTrafficSignHeight", 2);
			
			types.add(attributes);
		}

		/* create a visual representation for the traffic sign */

		if (types.size() > 0) {
			node.addRepresentation(new TrafficSign(node, types));
		}

	}

	private static boolean isInHighway(MapNode node){
		if (node.getConnectedWaySegments().size()>0){
			for(MapWaySegment way: node.getConnectedWaySegments()){
				if( way.getTags().containsKey("highway") && !way.getTags().containsAny("highway", asList("path", "footway", "platform") ) ){
					return true;
				}
			}
		}
		return false;
	}

	private String generateSignName(String country, String sign) {

		String signName = "";

		if(!country.isEmpty()) {
			signName = "SIGN_"+country+"_"+sign;
		}else {
			signName = "SIGN_"+sign;
		}

		return signName;
	}

	/**
	 * Parses configuration files for the traffic sign-specific keys
	 * trafficSign_NAME_numPosts|defaultHeight|material
	 *
	 * @param signName The sign name (country prefix and subtype/brackettext value)
	 * @return A TrafficSignType instance of the the parsed values
	 */
	private TrafficSignType mapSignAttributes(String signName) {

		String regex = "trafficSign_"+signName+"_(numPosts|defaultHeight|material)";

		String originalMaterial = "";
		int numPosts = 1;
		double defaultHeight = 0;

		Matcher matcher = null;

		@SuppressWarnings("unchecked")
		Iterator<String> keyIterator = config.getKeys();

		//parse traffic sign specific configuration values
		while (keyIterator.hasNext()) {

			String key = keyIterator.next();
			matcher = Pattern.compile(regex).matcher(key);

			if (matcher.matches()) {

				String attribute = matcher.group(1);

				if("material".equals(attribute)) {

					originalMaterial = config.getString(key, "").toUpperCase();

				} else if("numPosts".equals(attribute)) {

					numPosts = config.getInt(key, 1);

				} else if("defaultHeight".equals(attribute)) {

					defaultHeight = config.getDouble(key, 0);
				}
			}
		}

		return new TrafficSignType(originalMaterial, numPosts, defaultHeight);
	}

	/**
	 * Creates a replica of originalMaterial with a new textureDataList.
	 * The new list is a copy of the old one with its TextTextureData layers
	 * replaced by a new TextTextureData instance of different text.
	 * Returns the new ConfMaterial created.
	 *
	 * @param originalMaterial The ConfMaterial to replicate
	 * @param map A HashMap used to map each of traffic_sign.subtype / traffic_sign.brackettext
	 * to their corresponding values
	 * @param element The MapElement object to extract values from
	 * @return a ConfMaterial identical to originalMaterial with its textureDataList altered
	 */
	private static Material configureMaterial(ConfMaterial originalMaterial, Map<String, String> map, MapElement element) {

		if(originalMaterial == null) return null;

		Material newMaterial = null;
		List<TextureData> newList = new ArrayList<TextureData>(originalMaterial.getTextureDataList());

		String regex = ".*(%\\{(.+)\\}).*";
		Pattern pattern = Pattern.compile(regex);

		for(TextureData layer : originalMaterial.getTextureDataList()) {

			if(layer instanceof TextTextureData) {

				String newText = "";
				Matcher matcher = pattern.matcher(((TextTextureData) layer).text);
				int index = originalMaterial.getTextureDataList().indexOf(layer);

				if( matcher.matches() ) {

					newText = ((TextTextureData)layer).text;

					while(matcher.matches()) {

						if(element.getTags().containsKey(matcher.group(2))) {

							newText = newText.replace(matcher.group(1), element.getTags().getValue(matcher.group(2)));

						} else if(!map.isEmpty()) {

							for (String key : map.keySet()) {
								if(key.equals(matcher.group(2))) {
									newText = newText.replace(matcher.group(1), map.get(matcher.group(2)));
								}
							}

						} else {
							System.err.println("Unknown attribute: "+matcher.group(2));
							newText = newText.replace(matcher.group(1), "");
						}

						matcher = pattern.matcher(newText);
					}

					TextTextureData textData = new TextTextureData(newText, ((TextTextureData)layer).font, layer.width,
							layer.height, ((TextTextureData)layer).topOffset, ((TextTextureData)layer).leftOffset,
							((TextTextureData)layer).textColor, ((TextTextureData) layer).relativeFontSize,
							layer.wrap, layer.coordFunction, layer.colorable, layer.isBumpMap);

					newList.set(index, textData);
				}
			}
		}

		newMaterial = new ConfMaterial(originalMaterial.getInterpolation(),originalMaterial.getColor(),
						originalMaterial.getAmbientFactor(),originalMaterial.getDiffuseFactor(),originalMaterial.getSpecularFactor(),
						originalMaterial.getShininess(),originalMaterial.getTransparency(),originalMaterial.getShadow(),
						originalMaterial.getAmbientOcclusion(),newList);

		return newMaterial;
	}

	private static final class TrafficSign extends NoOutlineNodeWorldObject
			implements RenderableToAllTargets {

		private final List<TrafficSignType> types;

		public TrafficSign(MapNode node, List<TrafficSignType> types) {

			super(node);

			this.types = types;

		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void renderTo(Target<?> target) {

			/* get basic parameters */

			double height = parseHeight(node.getTags(), (float)types.get(0).defaultHeight);
			double postRadius = 0.05;

			double[] signHeights = new double[types.size()];
			double[] signWidths = new double[types.size()];

			for (int sign = 0; sign < types.size(); sign++) {

				TextureData textureData = null;

				if (types.get(sign).material.getNumTextureLayers() != 0) {
					textureData = types.get(sign).material.getTextureDataList().get(0);
				}

				if (textureData == null) {
					signHeights[sign] = 0.6;
					signWidths[sign] = 0.6;
				} else {
					signHeights[sign] = textureData.height;
					signWidths[sign] = textureData.width;
				}

			}

			/* position the post(s) */

			int numPosts = types.get(0).numPosts;

			List<VectorXYZ> positions = new ArrayList<VectorXYZ>(numPosts);

			for (int i = 0; i < numPosts; i++) {
				double relativePosition = 0.5 - (i+1)/(double)(numPosts+1);
				positions.add(getBase().add(X_UNIT.mult(relativePosition * signWidths[0])));
			}

			/* create the front and back side of the sign */

			List<List<VectorXYZ>> signGeometries = new ArrayList<List<VectorXYZ>>();

			double distanceBetweenSigns = 0.1;
			double upperHeight = height;

			for (int sign = 0; sign < types.size(); sign++) {

				double signHeight = signHeights[sign];
				double signWidth = signWidths[sign];

				List<VectorXYZ> vs = asList(
						getBase().add(+signWidth/2, upperHeight, postRadius),
						getBase().add(+signWidth/2, upperHeight-signHeight, postRadius),
						getBase().add(-signWidth/2, upperHeight, postRadius),
						getBase().add(-signWidth/2, upperHeight-signHeight, postRadius)
						);

				signGeometries.add(vs);

				upperHeight -= signHeight + distanceBetweenSigns;

			}

			/* rotate the sign around the base to match the direction tag */

			double direction = parseDirection(node.getTags(), PI);

			for (List<VectorXYZ> vs : signGeometries) {

				for (int i = 0; i < vs.size(); i++) {
					VectorXYZ v = vs.get(i);
					v = v.rotateVec(direction, getBase(), VectorXYZ.Y_UNIT);
					vs.set(i, v);
				}

			}

			if (positions.size() > 1) { // if 1, the post is exactly on the base
				for (int i = 0; i < positions.size(); i++) {
					VectorXYZ v = positions.get(i);
					v = v.rotateVec(direction, getBase(), VectorXYZ.Y_UNIT);
					positions.set(i, v);
				}
			}

			/* render the post(s) */

			for (VectorXYZ position : positions) {
				target.drawColumn(STEEL, null, position,
						height, postRadius, postRadius,
						false, true);
			}

			/* render the sign (front, then back) */

			for (int sign = 0; sign < types.size(); sign++) {

				TrafficSignType type = types.get(sign);
				List<VectorXYZ> vs = signGeometries.get(sign);

				target.drawTriangleStrip(type.material, vs,
						texCoordLists(vs, type.material, STRIP_FIT));

				vs = asList(vs.get(2), vs.get(3), vs.get(0), vs.get(1));

				target.drawTriangleStrip(STEEL, vs,
						texCoordLists(vs, STEEL, STRIP_FIT));

			}

		}

	}

}

